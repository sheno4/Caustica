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
    private record Ranked<T>(long key, Column<T> column, long distance) { }
    private static final class Column<T> {
        final Long2ObjectOpenHashMap<Candidate<T>> candidates = new Long2ObjectOpenHashMap<>();
        Ranked<T> rank;
        boolean available;
        int replacements;
    }
    private record Input<T>(long generation, Context context,
                            Long2ObjectOpenHashMap<Candidate<T>> changes,
                            Long2BooleanOpenHashMap columns, Retained<T> retained) { }

    private static final class Retained<T> {
        final Long2ObjectOpenHashMap<Candidate<T>> candidates = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<Column<T>> columns = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet loaded = new LongOpenHashSet();
        final TreeSet<Ranked<T>> eligible = new TreeSet<>(Comparator
                .comparingLong((Ranked<T> item) -> item.distance)
                .thenComparingLong(Ranked::key));
        Context context;
        int replacements;
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

    private static <T> Plan<T> prepare(Input<T> input) {
        Context context = input.context;
        Retained<T> state = input.retained;
        boolean rerank = state.context == null || context.x != state.context.x || context.z != state.context.z;
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
            long key = entry.getLongKey();
            long columnKey = RtTerrain.columnKey(RtTerrain.sectionX(key), RtTerrain.sectionZ(key));
            Column<T> column = state.columns.get(columnKey);
            Candidate<T> previous = state.candidates.remove(key);
            if (column != null) {
                column.candidates.remove(key);
                if (previous != null && previous.replacesVisible) {
                    column.replacements--;
                    state.replacements--;
                }
            }
            Candidate<T> candidate = entry.getValue();
            if (candidate != null) {
                state.candidates.put(key, candidate);
                if (column == null) {
                    column = new Column<>();
                    column.rank = rank(columnKey, column, context);
                    state.columns.put(columnKey, column);
                    affectedColumns.add(columnKey);
                }
                column.candidates.put(key, candidate);
                if (candidate.replacesVisible) {
                    column.replacements++;
                    state.replacements++;
                }
            } else if (column != null && column.candidates.isEmpty()) {
                state.eligible.remove(column.rank);
                state.columns.remove(columnKey);
            }
        }
        for (long key : affectedColumns) {
            Column<T> column = state.columns.get(key);
            if (column == null) continue;
            column.available = neighborsLoaded(state, (int) (key >> 32), (int) key);
            if (!rerank) {
                if (column.available) state.eligible.add(column.rank);
                else state.eligible.remove(column.rank);
            }
        }
        if (rerank) {
            state.eligible.clear();
            for (var entry : state.columns.long2ObjectEntrySet()) {
                Column<T> column = entry.getValue();
                column.rank = rank(entry.getLongKey(), column, context);
                if (column.available) state.eligible.add(column.rank);
            }
        }
        state.context = context;
        var selected = new ArrayList<Candidate<T>>(context.batchSize);
        if (state.replacements != 0) select(state, context, true, selected);
        if (selected.size() < context.batchSize) select(state, context, false, selected);
        return new Plan<>(context.epoch, List.copyOf(selected));
    }

    /** Equal horizontal distances share vertical and identity tie-breaking across columns. */
    private static <T> void select(Retained<T> state, Context context, boolean replacesVisible,
                                   ArrayList<Candidate<T>> selected) {
        var ring = new ArrayList<Candidate<T>>();
        long distance = -1;
        for (Ranked<T> rank : state.eligible) {
            int count = replacesVisible ? rank.column.replacements
                    : rank.column.candidates.size() - rank.column.replacements;
            if (count == 0) continue;
            if (rank.distance != distance) {
                append(ring, context, selected);
                if (selected.size() == context.batchSize) return;
                distance = rank.distance;
            }
            for (Candidate<T> candidate : rank.column.candidates.values()) {
                if (candidate.replacesVisible == replacesVisible) ring.add(candidate);
            }
        }
        append(ring, context, selected);
    }

    private static <T> void append(ArrayList<Candidate<T>> ring, Context context,
                                   ArrayList<Candidate<T>> selected) {
        ring.sort(Comparator.comparingLong((Candidate<T> candidate) ->
                RtTerrain.distance(candidate.key, context.x, context.y, context.z)).thenComparingLong(Candidate::key));
        int count = Math.min(ring.size(), context.batchSize - selected.size());
        for (int i = 0; i < count; i++) selected.add(ring.get(i));
        ring.clear();
    }

    private static <T> Ranked<T> rank(long key, Column<T> column, Context context) {
        long dx = (int) (key >> 32) - context.x, dz = (int) key - context.z;
        return new Ranked<>(key, column, dx * dx + dz * dz);
    }

    private static boolean neighborsLoaded(Retained<?> state, int x, int z) {
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
