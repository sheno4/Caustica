package dev.comfyfluffy.caustica.minecraft.client.terrain;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import jdk.jfr.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Worker-owned extraction priorities fed by coalesced request and loaded-column deltas. */
final class TerrainDispatchPlanner<T> {
    record Context(long epoch, int x, int y, int z, int minY, int maxY, int batchSize) { }
    record Candidate<T>(long key, TerrainUpdates.Request<T> request, boolean replacesVisible) { }
    record Plan<T>(long epoch, List<Candidate<T>> candidates) { }
    private record Ranked<T>(Candidate<T> candidate, long distance) { }
    private record Input<T>(long generation, Context context,
                            Long2ObjectOpenHashMap<Candidate<T>> changes,
                            Long2BooleanOpenHashMap columns, Retained<T> retained) { }

    private static final class Retained<T> {
        final Long2ObjectOpenHashMap<Ranked<T>> candidates = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet loaded = new LongOpenHashSet();
        final TreeSet<Ranked<T>> eligible = new TreeSet<>(Comparator
                .comparingInt((Ranked<T> item) -> item.candidate.replacesVisible ? 0 : 1)
                .thenComparingLong(Ranked::distance)
                .thenComparingLong(item -> item.candidate.key));
        Context context;
    }

    private final BiConsumer<Runnable, Runnable> executor;
    private final Consumer<Throwable> failure;
    private final Object mailbox = new Object();
    private final AtomicReference<Plan<T>> completed = new AtomicReference<>();
    private Long2ObjectOpenHashMap<Candidate<T>> changes = new Long2ObjectOpenHashMap<>();
    private Long2BooleanOpenHashMap columnChanges = new Long2BooleanOpenHashMap();
    private Context requestedContext;
    private long generation;
    private boolean scheduled;
    private boolean requested;
    // The worker owns each retained state. Reset replaces it so a running plan can finish in isolation.
    private Retained<T> retained = new Retained<>();

    TerrainDispatchPlanner(BiConsumer<Runnable, Runnable> executor, Consumer<Throwable> failure) {
        this.executor = executor;
        this.failure = failure;
    }

    /** Request fields are captured while the producer holds its preparation lock. */
    void pending(long key, TerrainUpdates.Request<T> request) {
        Candidate<T> candidate = request == null ? null : new Candidate<>(key, request, request.section.ready);
        synchronized (mailbox) { changes.put(key, candidate); }
    }

    void column(long key, boolean present) {
        synchronized (mailbox) { columnChanges.put(key, present); }
    }

    /** Render submits demand after accepted requests have emitted their removal deltas. */
    void request(Context context, boolean nextBatch) {
        boolean submit = false;
        synchronized (mailbox) {
            if (nextBatch || !context.equals(requestedContext) || !changes.isEmpty() || !columnChanges.isEmpty()) {
                requested = true;
            }
            requestedContext = context;
            if (requested && !scheduled) {
                scheduled = true;
                submit = true;
            }
        }
        if (submit) {
            try { executor.accept(this::prepare, this::cancelled); }
            catch (RuntimeException | Error problem) {
                synchronized (mailbox) { scheduled = false; }
                throw problem;
            }
        }
    }

    Plan<T> poll() { return completed.getAndSet(null); }

    private void cancelled() {
        synchronized (mailbox) { scheduled = false; }
    }

    /** Invalidates queued and running plans without waiting for the worker. */
    void reset() {
        synchronized (mailbox) {
            // Keep the scheduled worker: it must consume new demand or release its scheduling claim.
            generation++;
            requestedContext = null;
            requested = false;
            changes.clear();
            columnChanges.clear();
            completed.set(null);
            retained = new Retained<>();
        }
    }

    private void prepare() {
        try {
            while (true) {
                Input<T> input;
                synchronized (mailbox) {
                    if (!requested) {
                        scheduled = false;
                        return;
                    }
                    requested = false;
                    input = new Input<>(generation, requestedContext, changes, columnChanges, retained);
                    changes = new Long2ObjectOpenHashMap<>();
                    columnChanges = new Long2BooleanOpenHashMap();
                }
                var event = new DispatchPlanEvent();
                event.begin();
                long started = System.nanoTime();
                Plan<T> plan = prepare(input);
                event.elapsedNanos = System.nanoTime() - started;
                event.changedCandidates = input.changes.size();
                event.retainedCandidates = input.retained.candidates.size();
                event.selectedCandidates = plan.candidates.size();
                event.commit();
                synchronized (mailbox) {
                    if (generation == input.generation) completed.set(plan);
                }
            }
        } catch (Throwable problem) {
            synchronized (mailbox) { scheduled = false; }
            failure.accept(problem);
        }
    }

    private Plan<T> prepare(Input<T> input) {
        Context context = input.context;
        Retained<T> state = input.retained;
        boolean rerank = state.context == null || context.x != state.context.x
                || context.y != state.context.y || context.z != state.context.z;
        var affectedColumns = new LongOpenHashSet();
        for (var entry : input.columns.long2BooleanEntrySet()) {
            long column = entry.getLongKey();
            if (entry.getBooleanValue()) state.loaded.add(column);
            else state.loaded.remove(column);
            int x = (int) (column >> 32), z = (int) column;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                affectedColumns.add(RtTerrain.columnKey(x + dx, z + dz));
            }
        }
        for (var entry : input.changes.long2ObjectEntrySet()) {
            Ranked<T> previous = state.candidates.remove(entry.getLongKey());
            if (previous != null) state.eligible.remove(previous);
            Candidate<T> candidate = entry.getValue();
            if (candidate != null) {
                Ranked<T> next = rank(candidate, context);
                state.candidates.put(candidate.key, next);
                if (!rerank && available(state, candidate.key)) state.eligible.add(next);
            }
        }
        if (rerank) {
            state.eligible.clear();
            for (var entry : state.candidates.long2ObjectEntrySet()) {
                Ranked<T> next = rank(entry.getValue().candidate, context);
                entry.setValue(next);
                if (available(state, next.candidate.key)) state.eligible.add(next);
            }
        } else {
            for (long column : affectedColumns) {
                int x = (int) (column >> 32), z = (int) column;
                boolean available = neighborsLoaded(state, x, z);
                for (int y = context.minY; y <= context.maxY; y++) {
                    Ranked<T> candidate = state.candidates.get(RtTerrain.sectionKey(x, y, z));
                    if (candidate == null) continue;
                    if (available) state.eligible.add(candidate);
                    else state.eligible.remove(candidate);
                }
            }
        }
        state.context = context;
        var selected = new ArrayList<Candidate<T>>(Math.min(context.batchSize, state.eligible.size()));
        for (Ranked<T> candidate : state.eligible) {
            if (selected.size() == context.batchSize) break;
            selected.add(candidate.candidate);
        }
        return new Plan<>(context.epoch, List.copyOf(selected));
    }

    private Ranked<T> rank(Candidate<T> candidate, Context context) {
        return new Ranked<>(candidate, RtTerrain.distance(candidate.key, context.x, context.y, context.z));
    }

    private boolean available(Retained<T> state, long key) {
        return neighborsLoaded(state, RtTerrain.sectionX(key), RtTerrain.sectionZ(key));
    }

    private boolean neighborsLoaded(Retained<T> state, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!state.loaded.contains(RtTerrain.columnKey(x + dx, z + dz))) return false;
        }
        return true;
    }

    @Name("dev.comfyfluffy.caustica.TerrainDispatchPlan")
    @Label("Terrain worker dispatch selection") @Category({"Caustica", "Terrain"})
    @StackTrace(false) @Enabled(false)
    static final class DispatchPlanEvent extends Event {
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
        int changedCandidates, retainedCandidates, selectedCandidates;
    }
}
