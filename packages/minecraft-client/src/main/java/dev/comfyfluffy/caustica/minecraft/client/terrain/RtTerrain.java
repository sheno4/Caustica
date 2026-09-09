package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainGeometry;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainLightAdapter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import jdk.jfr.EventType;

import static dev.comfyfluffy.caustica.minecraft.client.terrain.TerrainEvents.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrainMesher.WORKER_TESS;
import static dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrainMesher.buildCpuSection;

/**
 * Retains the previous section revisions until a complete neighboring-section group can replace them.
 * The render thread captures immutable world snapshots; workers prepare and publish complete groups.
 */
public final class RtTerrain {
    private static final EventType TERRAIN_STATE_EVENT = EventType.getEventType(TerrainStateEvent.class);
    private static final EventType TERRAIN_JOB_EVENT = EventType.getEventType(TerrainJobEvent.class);
    private static final EventType TERRAIN_PUBLICATION_EVENT = EventType.getEventType(TerrainPublicationEvent.class);
    private static final long FRAME_FALLBACK_NANOS = 200_000_000L;

    private final RtWorkerPool workers;
    private final MinecraftTelemetry.Instrumentation instrumentation;
    private final RtSectionSnapshots snapshots;
    private final TerrainUpdates<Build> updates;
    private final TerrainDispatchPlanner<Build> dispatchPlanner;
    private final TerrainWindow window = new TerrainWindow();
    private final ConcurrentLinkedQueue<TerrainWindow.Observation> pendingWindows = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean windowScheduled = new AtomicBoolean();
    private final ConcurrentLinkedQueue<List<Long>> dirty = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Build> discarded = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean publicationScheduled = new AtomicBoolean();
    private final AtomicInteger outstandingBuilds = new AtomicInteger();
    private volatile MinecraftTerrainGeometry geometry;
    private volatile Throwable workerFailure;
    private volatile MinecraftMaterialLookup materials;
    private volatile boolean clearRequested;
    private volatile long epoch;
    private ClientLevel world;
    private final Object preparationLock = new Object();
    private final Object publicationLock = new Object();
    private int lowY;
    private int highY;
    private long lastFrame;
    private long revision;
    private TerrainDispatchPlanner.Plan<Build> dispatchPlan;
    private int dispatchCursor;
    private volatile StateCounts stateCounts = StateCounts.EMPTY;
    public int blockX;
    public int blockY;
    public int blockZ;

    public RtTerrain(RtWorkerPool workers, MinecraftTelemetry.Instrumentation instrumentation) {
        this.workers = workers;
        this.instrumentation = instrumentation;
        snapshots = new RtSectionSnapshots(instrumentation);
        dispatchPlanner = new TerrainDispatchPlanner<>(workers::submitPlanning, failure -> workerFailure = failure);
        updates = new TerrainUpdates<>(this::discardBuild, dispatchPlanner::pending);
    }

    public RtTerrain currentOrNull() { return world == null ? null : this; }

    public boolean isSectionReady(BlockPos position) {
        long key = sectionKey(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
        return world != null && updates.isReady(key);
    }

    public SceneOrigin sceneOrigin() { return new SceneOrigin(blockX, blockY, blockZ); }

    public void bindGeometry(MinecraftTerrainGeometry geometry) { this.geometry = geometry; }

    public void unbindGeometry(MinecraftTerrainGeometry geometry) {
        if (this.geometry == geometry) {
            this.geometry = null;
            reset();
            workers.shutdown();
        }
    }

    public void publishMaterialLookup(MinecraftMaterialLookup lookup) {
        materials = lookup;
        clearRequested = true;
    }

    public void clearMaterialLookup() {
        materials = null;
        clearRequested = true;
    }

    public void requestFullClear() {
        clearRequested = true;
    }

    /** Outstanding extraction, upload and GPU preparation across epochs; sampled without waiting for workers. */
    public int outstandingBuilds() {
        return outstandingBuilds.get();
    }

    /** Border culling and fluid heights depend on the block immediately across a section boundary. */
    public void markBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        var keys = new ArrayList<Long>();
        for (int x = (minX - 1) >> 4; x <= (maxX + 1) >> 4; x++) {
            for (int y = (minY - 1) >> 4; y <= (maxY + 1) >> 4; y++) {
                for (int z = (minZ - 1) >> 4; z <= (maxZ + 1) >> 4; z++) {
                    keys.add(sectionKey(x, y, z));
                }
            }
        }
        updates.invalidate(keys);
        dirty.add(keys);
        coordinate(() -> updates.dirty(keys));
    }

    public void update() {
        long started = instrumentation.startStage();
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel nextWorld = mc.player == null ? null : mc.level;
            if (clearRequested || world != nextWorld) {
                clearRequested = false;
                reset();
                world = nextWorld;
            }
            if (world == null || materials == null) {
                recordState(mc);
                return;
            }
            synchronizeWindow(mc);
            drainDirty();
            if (System.nanoTime() - lastFrame > FRAME_FALLBACK_NANOS) stream(mc);
            recordState(mc);
        } finally {
            instrumentation.endStage("terrain.tick", started);
        }
    }

    private void recordState(Minecraft mc) {
        if (!TERRAIN_STATE_EVENT.isEnabled()) return;
        coordinate(this::captureState);
        var counts = stateCounts;
        TerrainStateEvent event = new TerrainStateEvent();
        event.observedFrameId = instrumentation.frameSerial();
        event.worldPresent = world != null;
        event.materialsPresent = materials != null;
        event.geometryBound = geometry != null;
        event.epoch = epoch;
        event.lastDispatchedRevision = revision;
        if (mc.player != null) {
            event.playerBlockX = mc.player.getBlockX();
            event.playerBlockY = mc.player.getBlockY();
            event.playerBlockZ = mc.player.getBlockZ();
        }
        event.windowCenterChunkX = event.playerBlockX >> 4;
        event.windowCenterChunkZ = event.playerBlockZ >> 4;
        event.renderDistanceChunks = mc.options.getEffectiveRenderDistance();
        event.minSectionY = lowY;
        event.maxSectionY = highY;
        event.originBlockX = blockX;
        event.originBlockY = blockY;
        event.originBlockZ = blockZ;
        event.loadedWindowColumns = counts.columns;
        event.trackedSections = counts.sections;
        event.residentGeometrySections = counts.geometry;
        event.wantedSections = counts.wanted;
        event.removingSections = counts.removing;
        event.publishedSections = counts.published;
        event.requests = counts.requests;
        event.completedRequests = counts.complete;
        event.dispatchedRequests = counts.dispatched;
        event.undispatchedRequests = counts.pending;
        event.neighborBlockedRequests = counts.blocked;
        event.pendingGroups = counts.groups;
        event.readyGroups = counts.ready;
        event.neighborBlockedColumns = counts.blockedColumns;
        var workerState = workers.state();
        event.workerThreads = workerState.threads();
        event.activeWorkers = workerState.active();
        event.queuedWorkerTasks = workerState.queued();
        event.outstandingBuilds = outstandingBuilds.get();
        event.discardedQueue = discarded.size();
        event.publicationScheduled = publicationScheduled.get();
        event.dirtyGroupsQueue = dirty.size();
        event.commit();
    }

    private void captureState() {
        int wanted = 0, removing = 0, published = 0, requests = 0, complete = 0, dispatched = 0, pending = 0, blocked = 0;
        var groups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TerrainUpdates.Group<Build>, Boolean>());
        var blockedColumns = new LongOpenHashSet();
        var checkedColumns = new LongOpenHashSet();
        for (var section : updates.sections.values()) {
            if (section.wanted) wanted++; else removing++;
            if (section.ready) published++;
            var request = section.request;
            if (request == null) continue;
            requests++;
            groups.add(request.group);
            if (request.complete()) complete++;
            else if (request.dispatched()) dispatched++;
            else {
                pending++;
                long column = columnKey(sectionX(section.key), sectionZ(section.key));
                if (checkedColumns.add(column) && !window.neighborsLoaded(sectionX(section.key), sectionZ(section.key))) {
                    blockedColumns.add(column);
                }
                if (blockedColumns.contains(column)) blocked++;
            }
        }
        int ready = 0;
        for (var group : groups) if (group.remaining == 0) ready++;
        stateCounts = new StateCounts(window.columns(), updates.sections.size(),
                geometry == null ? 0 : geometry.sectionKeys().size(), wanted, removing, published,
                requests, complete, dispatched, pending, blocked, groups.size(), ready, blockedColumns.size());
    }

    private record StateCounts(int columns, int sections, int geometry, int wanted, int removing, int published,
                               int requests, int complete, int dispatched, int pending, int blocked,
                               int groups, int ready, int blockedColumns) {
        static final StateCounts EMPTY = new StateCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private void recordJob(Build build, String action) {
        recordJob(build.request, build.epoch, build.revision, action, build.failure != null);
    }

    private void discardBuild(Build build) {
        recordJob(build, "discarded");
        discarded.add(build);
    }

    private void recordJob(TerrainUpdates.Request<Build> request, long jobEpoch, long jobRevision,
                           String action, boolean failed) {
        if (!TERRAIN_JOB_EVENT.isEnabled()) return;
        TerrainJobEvent event = new TerrainJobEvent();
        event.observedFrameId = instrumentation.frameSerial();
        event.epoch = jobEpoch;
        event.revision = jobRevision;
        event.sectionX = sectionX(request.section.key);
        event.sectionY = sectionY(request.section.key);
        event.sectionZ = sectionZ(request.section.key);
        event.action = action;
        event.failed = failed;
        event.commit();
    }

    public void frame() {
        long started = instrumentation.startStage();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (clearRequested || world == null || mc.level != world || mc.player == null || materials == null) return;
            lastFrame = System.nanoTime();
            drainDirty();
            stream(mc);
        } finally {
            instrumentation.endStage("terrain.frame", started);
        }
    }

    private void synchronizeWindow(Minecraft mc) {
        int minY = world.getMinY() >> 4;
        int maxY = (world.getMinY() + world.getHeight() - 1) >> 4;
        int radius = Math.max(1, mc.options.getEffectiveRenderDistance());
        int cx = mc.player.getBlockX() >> 4;
        int cz = mc.player.getBlockZ() >> 4;
        var available = new it.unimi.dsi.fastutil.longs.LongArrayList();
        ClientChunkCache chunks = world.getChunkSource();
        for (int x = cx - radius - 1; x <= cx + radius + 1; x++) {
            for (int z = cz - radius - 1; z <= cz + radius + 1; z++) {
                if (!chunks.hasChunk(x, z)) continue;
                long column = columnKey(x, z);
                available.add(column);
            }
        }
        pendingWindows.add(new TerrainWindow.Observation(epoch, cx, cz, radius, minY, maxY, available.toLongArray()));
        scheduleWindow();
        lowY = minY;
        highY = maxY;
        int distance = CausticaConfig.get(MinecraftOptions.Rt.Terrain.REBASE_DISTANCE_BLOCKS);
        int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
        if (Math.abs(x - blockX) >= distance || Math.abs(y - blockY) >= distance
                || Math.abs(z - blockZ) >= distance) {
            blockX = x;
            blockY = y;
            blockZ = z;
        }
    }

    private void scheduleWindow() {
        if (pendingWindows.isEmpty() || !windowScheduled.compareAndSet(false, true)) return;
        workers.submitCoordination(() -> {
            try {
                var observed = pendingWindows.poll();
                synchronized (preparationLock) {
                    if (observed != null && observed.epoch() == epoch) {
                        window.apply(observed, updates::want, updates::remove, dispatchPlanner::column, invalidated -> {
                            if (!invalidated.isEmpty()) dirty.add(invalidated);
                        });
                    }
                }
                schedulePublication();
            } catch (Throwable failure) {
                workerFailure = failure;
            } finally {
                windowScheduled.set(false);
                scheduleWindow();
            }
        }, () -> windowScheduled.set(false));
    }

    private void coordinate(Runnable work) { coordinate(work, () -> { }); }

    private void coordinate(Runnable work, Runnable cancelled) {
        long requestedEpoch = epoch;
        workers.submitCoordination(() -> {
            try {
                synchronized (preparationLock) {
                    if (requestedEpoch != epoch) { cancelled.run(); return; }
                    work.run();
                }
                schedulePublication();
            } catch (Throwable failure) {
                workerFailure = failure;
            }
        }, cancelled);
    }

    private void drainDirty() {
        List<Long> changed;
        while ((changed = dirty.poll()) != null) changed.forEach(snapshots::invalidate);
    }

    private void stream(Minecraft mc) {
        if (geometry == null) return;
        MinecraftMaterialLookup lookup = materials;
        var settings = CausticaConfig.snapshot();
        checkWorkerFailure();
        long dispatchStarted = instrumentation.startStage();
        try {
            dispatch(mc, lookup, settings);
        } finally {
            instrumentation.endStage("terrain.snapshotDispatch", dispatchStarted);
        }
    }

    private void checkWorkerFailure() {
        Throwable failure = workerFailure;
        if (failure != null) throw new IllegalStateException("Terrain preparation/publication failed", failure);
    }

    private void dispatch(Minecraft mc, MinecraftMaterialLookup lookup,
                          OptionValues settings) {
        int cx = mc.player.getBlockX() >> 4, cy = mc.player.getBlockY() >> 4, cz = mc.player.getBlockZ() >> 4;
        int batchSize = settings.get(MinecraftOptions.Rt.Terrain.ASYNC_DISPATCH_PER_PASS);
        var context = new TerrainDispatchPlanner.Context(epoch, cx, cy, cz, lowY, highY, batchSize);
        int slots = Math.min(settings.get(MinecraftOptions.Rt.Terrain.ASYNC_DISPATCH_PER_PASS),
                settings.get(MinecraftOptions.Rt.Terrain.MAX_INFLIGHT_SECTIONS) - outstandingBuilds.get());
        if (slots <= 0) {
            dispatchPlanner.request(context, false);
            return;
        }
        var completed = dispatchPlanner.poll();
        if (completed != null) {
            dispatchPlan = completed;
            dispatchCursor = 0;
        }
        if (dispatchPlan != null && dispatchPlan.epoch() != epoch) dispatchPlan = null;
        // Extraction observes invalidations queued before this pass, regardless of when selection finished.
        drainDirty();
        boolean nextBatch = false;
        if (dispatchPlan != null) {
            var readyColumns = new LongOpenHashSet();
            var blockedColumns = new LongOpenHashSet();
            int accepted = 0;
            while (dispatchCursor < dispatchPlan.candidates().size() && accepted < slots) {
                var candidate = dispatchPlan.candidates().get(dispatchCursor++);
                if (!candidate.request().awaitingExtraction()) continue;
                long key = candidate.key();
                int x = sectionX(key), z = sectionZ(key);
                long column = columnKey(x, z);
                if (blockedColumns.contains(column)) continue;
                if (!readyColumns.contains(column)) {
                    if (!observeNeighborsLoaded(world.getChunkSource(), x, z)) {
                        blockedColumns.add(column);
                        continue;
                    }
                    readyColumns.add(column);
                }
                if (dispatch(candidate.request(), lookup, mc)) accepted++;
            }
            if (dispatchCursor == dispatchPlan.candidates().size()) {
                nextBatch = !dispatchPlan.candidates().isEmpty();
                dispatchPlan = null;
            }
        }
        dispatchPlanner.request(context, nextBatch);
    }

    private boolean dispatch(TerrainUpdates.Request<Build> request, MinecraftMaterialLookup lookup, Minecraft mc) {
        long taskEpoch = epoch;
        if (!request.reserve()) return false;
        long key = request.section.key;
        int x = sectionX(key), y = sectionY(key), z = sectionZ(key);
        long paletteStarted = instrumentation.startStage();
        RtSectionSnapshots.Region region;
        try {
            region = snapshots.createRegion(world, x, y, z);
        } catch (RuntimeException | Error failure) {
            coordinate(() -> updates.retry(request));
            throw failure;
        } finally {
            instrumentation.endStage("terrain.paletteCapture", paletteStarted);
        }
        var models = mc.getModelManager().getBlockStateModelSet();
        var fluids = mc.getModelManager().getFluidStateModelSet();
        var colors = mc.getBlockColors();
        long taskRevision = ++revision;
        Object extraction = instrumentation.extraction(MinecraftTelemetry.GeometrySource.TERRAIN, 1);
        if (taskEpoch != epoch || !request.extracted()) return false;
        coordinate(() -> updates.dispatched(request));
        instrumentation.count("sectionsSnapshotted", 1);
        recordJob(request, taskEpoch, taskRevision, "dispatch", false);
        var build = new Build(request, taskEpoch, lookup.epoch(), taskRevision, null, extraction, null, null);
        try {
            submitBuild(build, geometry, () -> {
                var state = WORKER_TESS.get();
                state.reset(colors);
                return buildCpuSection(region, models, fluids, state, lookup, x, y, z);
            });
        } catch (RuntimeException | Error failure) {
            coordinate(() -> updates.retry(request));
            throw failure;
        }
        return true;
    }

    /** One terminal result owns the dispatch slot through CPU extraction, upload, and GPU preparation. */
    void submitBuild(Build build, MinecraftTerrainGeometry target, Supplier<RtTerrainMesher.CpuSection> extract) {
        outstandingBuilds.incrementAndGet();
        try {
            workers.submit(() -> {
                try {
                    recordJob(build, "cpu-start");
                    if (build.epoch != epoch) {
                        recordJob(build, "cpu-cancelled-epoch");
                        completeBuild(build);
                        return;
                    }
                    var cpu = extract.get();
                    recordJob(build, "cpu-ready");
                    Object ready = instrumentation.extraction(MinecraftTelemetry.GeometrySource.TERRAIN_READY, 1);
                    if (cpu.mesh() == null || build.epoch != epoch) {
                        if (cpu.mesh() == null) recordJob(build, "empty-ready");
                        completeBuild(build.result(ready, null, null));
                        return;
                    }
                    long key = build.request.section.key;
                    int x = sectionX(key) << 4, y = sectionY(key) << 4, z = sectionZ(key) << 4;
                    var put = new MinecraftTerrainGeometry.Put(key, x, y, z, cpu.mesh(),
                            MinecraftTerrainLightAdapter.describe(key, build.revision, x, y, z, cpu.lights()));
                    recordJob(build, "gpu-prepare-start");
                    var preparation = target.prepare(put);
                    recordJob(build, "gpu-prepare-submitted");
                    preparation.whenComplete((mesh, failure) -> {
                        var result = build.result(ready, mesh, failure);
                        recordJob(result, "gpu-prepare-ready");
                        completeBuild(result);
                    });
                } catch (Throwable failure) {
                    var result = build.result(null, null, failure);
                    recordJob(result, "cpu-failed");
                    completeBuild(result);
                }
            }, outstandingBuilds::decrementAndGet);
        } catch (RuntimeException | Error failure) {
            outstandingBuilds.decrementAndGet();
            throw failure;
        }
    }

    private void completeBuild(Build build) {
        outstandingBuilds.decrementAndGet();
        if (build.epoch != epoch || geometry == null) {
            recordJob(build, "prepared-result-stale");
            build.close();
            return;
        }
        coordinate(() -> {
            var lookup = materials;
            if (build.epoch != epoch || (lookup != null && !build.materialEpoch.equals(lookup.epoch()))
                    || build.request.section.request != build.request || !build.request.valid()) {
                recordJob(build, "prepared-result-stale");
                discardBuild(build);
            } else if (build.failure != null) {
                workerFailure = build.failure;
                discardBuild(build);
            } else if (!updates.complete(build.request, build)) discardBuild(build);
        }, build::close);
    }

    private void schedulePublication() {
        synchronized (preparationLock) {
            if (publicationScheduled.get() || geometry == null || clearRequested
                    || (!updates.hasReadyGroup() && discarded.isEmpty())
                    || !publicationScheduled.compareAndSet(false, true)) return;
            workers.submitPublication(this::publishReady, () -> publicationScheduled.set(false));
        }
    }

    private void publishReady() {
        TerrainPublicationEvent event = TERRAIN_PUBLICATION_EVENT.isEnabled() ? new TerrainPublicationEvent() : null;
        long cpuStarted = event == null ? -1 : threadCpuNanos();
        long allocatedStarted = event == null ? -1 : threadAllocatedBytes();
        if (event != null) {
            event.observedFrameId = instrumentation.frameSerial();
            event.begin();
        }
        synchronized (publicationLock) {
            try {
                List<TerrainUpdates.Group<Build>> groups;
                MinecraftTerrainGeometry target;
                long publishingEpoch;
                synchronized (preparationLock) {
                    groups = updates.ready();
                    target = geometry;
                    publishingEpoch = epoch;
                }
                if (event != null) {
                    event.epoch = publishingEpoch;
                    event.groups = groups.size();
                    event.sections = groups.stream().mapToInt(group -> group.requests.size()).sum();
                }
                if (target == null || groups.isEmpty()) return;
                var changes = new ArrayList<MinecraftTerrainGeometry.ReadyChange>();
                for (var group : groups) {
                    for (var request : group.requests) {
                        Build build = request.result;
                        if (build != null && build.prepared != null) changes.add(build.prepared);
                        else changes.add(new MinecraftTerrainGeometry.Drop(request.section.key));
                    }
                }
                try (var edit = target.prepareEdit(changes)) {
                    synchronized (preparationLock) {
                        if (epoch != publishingEpoch || geometry != target || clearRequested) return;
                        for (var group : groups) {
                            for (var request : group.requests) {
                                if (request.section.request != request || !request.valid()) return;
                            }
                        }
                        if (!updates.claimPublication(groups)) return;
                        try {
                            edit.publish();
                            updates.published(groups);
                        } finally {
                            updates.releasePublication(groups);
                        }
                        if (event != null) event.published = true;
                    }
                }
                for (var group : groups) {
                    for (var request : group.requests) {
                        if (request.result != null) {
                            recordJob(request.result, "published");
                            instrumentation.published(request.result.extraction);
                            instrumentation.published(request.result.ready);
                        }
                    }
                }
            } catch (Throwable failure) {
                if (event != null) event.failed = true;
                workerFailure = failure;
            } finally {
                drainDiscardedBuilds();
                if (event != null) {
                    event.cpuNanos = cpuStarted < 0 ? -1 : threadCpuNanos() - cpuStarted;
                    event.allocatedBytes = allocatedStarted < 0 ? -1 : threadAllocatedBytes() - allocatedStarted;
                    event.commit();
                }
                publicationScheduled.set(false);
                if (workerFailure == null) schedulePublication();
            }
        }
    }

    private void reset() {
        epoch++;
        pendingWindows.clear();
        workers.coordinateAndWait(() -> {
            synchronized (publicationLock) {
                synchronized (preparationLock) {
                    updates.clear();
                    dispatchPlanner.reset();
                    window.clear();
                    workerFailure = null;
                    stateCounts = StateCounts.EMPTY;
                }
                drainDiscardedBuilds();
                if (geometry != null) {
                    var drops = geometry.sectionKeys().stream()
                            .map(key -> (MinecraftTerrainGeometry.ReadyChange) new MinecraftTerrainGeometry.Drop(key)).toList();
                    if (!drops.isEmpty()) geometry.edit(drops);
                }
            }
        });
        snapshots.clear();
        dispatchPlan = null;
        dispatchCursor = 0;
        dirty.clear();
    }

    public void shutdown() {
        geometry = null;
        reset();
        workers.shutdown();
        drainDiscardedBuilds();
        dirty.clear();
        snapshots.clear();
        dispatchPlan = null;
        dispatchCursor = 0;
        world = null;
    }

    private void drainDiscardedBuilds() {
        Build build;
        while ((build = discarded.poll()) != null) build.close();
    }

    private boolean observeNeighborsLoaded(ClientChunkCache chunks, int x, int z) {
        boolean ready = true;
        long[] keys = new long[9];
        boolean[] present = new boolean[9];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                keys[index] = columnKey(x + dx, z + dz);
                present[index] = chunks.hasChunk(x + dx, z + dz);
                ready &= present[index++];
            }
        }
        coordinate(() -> {
            for (int i = 0; i < keys.length; i++) {
                window.observe(keys[i], present[i], dispatchPlanner::column, dirty::add);
            }
        });
        return ready;
    }

    static long distance(long key, int x, int y, int z) {
        long dx = sectionX(key) - x, dz = sectionZ(key) - z;
        return ((dx * dx + dz * dz) << 16) + Math.abs(sectionY(key) - y);
    }

    static long columnKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    static long sectionKey(int x, int y, int z) {
        return (x & 0x3ffffffL) | ((z & 0x3ffffffL) << 26) | ((y & 0xfffL) << 52);
    }

    static int sectionX(long key) { return (int) (key << 38 >> 38); }
    static int sectionY(long key) { return (int) (key >> 52); }
    static int sectionZ(long key) { return (int) (key << 12 >> 38); }

    record Build(TerrainUpdates.Request<Build> request, long epoch, ResourcePackEpoch materialEpoch,
                 long revision, Throwable failure, Object extraction, Object ready,
                 MinecraftTerrainGeometry.Prepared prepared) {
        Build result(Object ready, MinecraftTerrainGeometry.Prepared prepared, Throwable failure) {
            return new Build(request, epoch, materialEpoch, revision, failure, extraction, ready, prepared);
        }
        void close() { if (prepared != null) prepared.close(); }
    }
}
