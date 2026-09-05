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

import jdk.jfr.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrainMesher.WORKER_TESS;
import static dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrainMesher.buildCpuSection;

/**
 * Retains the previous section revisions until a complete neighboring-section group can replace them.
 * World capture and publication run on the render thread; workers read immutable region snapshots.
 */
public final class RtTerrain {
    private static final EventType TERRAIN_STATE_EVENT = EventType.getEventType(TerrainStateEvent.class);
    private static final EventType TERRAIN_JOB_EVENT = EventType.getEventType(TerrainJobEvent.class);
    private static final int PUBLICATION_BUDGET = 8;
    private static final long FRAME_FALLBACK_NANOS = 200_000_000L;

    private final RtWorkerPool workers;
    private final MinecraftTelemetry.Instrumentation instrumentation;
    private final RtSectionSnapshots snapshots;
    private final TerrainUpdates<Build> updates = new TerrainUpdates<>(Build::close);
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<List<Long>> dirty = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Build> completed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger outstandingBuilds = new AtomicInteger();
    private MinecraftTerrainGeometry geometry;
    private volatile MinecraftMaterialLookup materials;
    private volatile boolean clearRequested;
    private volatile long epoch;
    private ClientLevel world;
    private final ConcurrentLinkedQueue<Build> prepared = new ConcurrentLinkedQueue<>();
    private final Object preparationLock = new Object();
    private int lowY;
    private int highY;
    private long lastFrame;
    private long revision;
    public int blockX;
    public int blockY;
    public int blockZ;

    public RtTerrain(RtWorkerPool workers, MinecraftTelemetry.Instrumentation instrumentation) {
        this.workers = workers;
        this.instrumentation = instrumentation;
        snapshots = new RtSectionSnapshots(instrumentation);
    }

    public RtTerrain currentOrNull() { return world == null ? null : this; }

    public boolean isSectionReady(BlockPos position) {
        long key = sectionKey(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
        var section = updates.sections.get(key);
        return world != null && section != null && section.ready;
    }

    public SceneOrigin sceneOrigin() { return new SceneOrigin(blockX, blockY, blockZ); }

    public void bindGeometry(MinecraftTerrainGeometry geometry) { this.geometry = geometry; }

    public void unbindGeometry(MinecraftTerrainGeometry geometry) {
        if (this.geometry == geometry) {
            this.geometry = null;
            reset();
        }
    }

    public void publishMaterialLookup(MinecraftMaterialLookup lookup) {
        materials = lookup;
        clearRequested = true;
    }

    public void clearMaterialLookup() { materials = null; }

    public void requestFullClear() { clearRequested = true; }

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
        dirty.add(keys);
    }

    public void update() {
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
    }

    private void recordState(Minecraft mc) {
        if (!TERRAIN_STATE_EVENT.isEnabled()) return;
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
        event.loadedWindowColumns = columns.size();
        event.trackedSections = updates.sections.size();
        event.residentGeometrySections = geometry == null ? 0 : geometry.sectionKeys().size();
        var groups = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<TerrainUpdates.Group<Build>, Boolean>());
        var blockedColumns = new LongOpenHashSet();
        var checkedColumns = new LongOpenHashSet();
        for (var section : updates.sections.values()) {
            if (section.wanted) event.wantedSections++;
            else event.removingSections++;
            if (section.ready) event.publishedSections++;
            var request = section.request;
            if (request == null) continue;
            event.requests++;
            groups.add(request.group);
            if (request.complete) event.completedRequests++;
            else if (request.dispatched) event.dispatchedRequests++;
            else {
                event.undispatchedRequests++;
                long column = columnKey(sectionX(section.key), sectionZ(section.key));
                if (world != null && checkedColumns.add(column)
                        && !neighborsLoaded(world.getChunkSource(), sectionX(section.key), sectionZ(section.key))) {
                    blockedColumns.add(column);
                }
                if (blockedColumns.contains(column)) event.neighborBlockedRequests++;
            }
        }
        event.pendingGroups = groups.size();
        for (var group : groups) {
            if (group.requests.stream().allMatch(request -> request.complete)) event.readyGroups++;
        }
        event.neighborBlockedColumns = blockedColumns.size();
        var workerState = workers.state();
        event.workerThreads = workerState.threads();
        event.activeWorkers = workerState.active();
        event.queuedWorkerTasks = workerState.queued();
        event.outstandingBuilds = outstandingBuilds.get();
        event.cpuCompletedQueue = completed.size();
        event.preparedQueue = prepared.size();
        event.dirtyGroupsQueue = dirty.size();
        event.commit();
    }

    private void recordJob(Build build, String action) {
        recordJob(build.request, build.epoch, build.revision, action, build.failure != null);
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

    @Name("dev.comfyfluffy.caustica.TerrainJob")
    @Label("Terrain section job observation") @Category({"Caustica", "Terrain"}) @StackTrace(false) @Enabled(false)
    static final class TerrainJobEvent extends Event {
        @Description("Most recently observed telemetry frame serial on the emitting thread")
        public long observedFrameId;
        public long epoch, revision;
        public int sectionX, sectionY, sectionZ;
        public String action;
        public boolean failed;
    }

    @Name("dev.comfyfluffy.caustica.TerrainState")
    @Label("Terrain state at client tick") @Category({"Caustica", "Terrain"}) @StackTrace(false) @Enabled(false)
    static final class TerrainStateEvent extends Event {
        @Description("Most recently observed telemetry frame serial; tick snapshot is not a rendered frame")
        public long observedFrameId;
        public boolean worldPresent, materialsPresent, geometryBound;
        public long epoch, lastDispatchedRevision;
        public int playerBlockX, playerBlockY, playerBlockZ;
        public int windowCenterChunkX, windowCenterChunkZ, renderDistanceChunks;
        public int minSectionY, maxSectionY;
        public int originBlockX, originBlockY, originBlockZ;
        public int loadedWindowColumns, trackedSections, wantedSections, removingSections;
        @Description("Published section state, including sections with empty geometry")
        public int publishedSections;
        public int residentGeometrySections;
        public int requests, undispatchedRequests, dispatchedRequests, completedRequests;
        public int pendingGroups, readyGroups, neighborBlockedRequests, neighborBlockedColumns;
        @Description("Outstanding CPU work, undrained CPU results, and GPU preparations across epochs")
        public int outstandingBuilds;
        @Description("Concurrent queue sizes are individually sampled, not an atomic worker snapshot")
        public int cpuCompletedQueue;
        public int preparedQueue, dirtyGroupsQueue;
        public int workerThreads, activeWorkers, queuedWorkerTasks;
    }

    public void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (clearRequested || world == null || mc.level != world || mc.player == null || materials == null) return;
        lastFrame = System.nanoTime();
        drainDirty();
        stream(mc);
    }

    private void synchronizeWindow(Minecraft mc) {
        int minY = world.getMinY() >> 4;
        int maxY = (world.getMinY() + world.getHeight() - 1) >> 4;
        int radius = Math.max(1, mc.options.getEffectiveRenderDistance());
        int cx = mc.player.getBlockX() >> 4;
        int cz = mc.player.getBlockZ() >> 4;
        var loaded = new LongOpenHashSet();
        ClientChunkCache chunks = world.getChunkSource();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (chunks.hasChunk(x, z)) loaded.add(columnKey(x, z));
            }
        }
        boolean heightChanged = minY != lowY || maxY != highY;
        for (long column : columns) {
            if (!heightChanged && loaded.contains(column)) continue;
            for (int y = lowY; y <= highY; y++) {
                long key = sectionKey((int) (column >> 32), y, (int) column);
                snapshots.invalidate(key);
                updates.remove(key);
            }
        }
        for (long column : loaded) {
            if (!heightChanged && columns.contains(column)) continue;
            for (int y = minY; y <= maxY; y++) {
                updates.want(sectionKey((int) (column >> 32), y, (int) column));
            }
        }
        columns.clear();
        columns.addAll(loaded);
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

    private void drainDirty() {
        List<Long> changed;
        while ((changed = dirty.poll()) != null) {
            changed.forEach(snapshots::invalidate);
            updates.dirty(changed);
        }
    }

    private void stream(Minecraft mc) {
        if (geometry == null) return;
        MinecraftMaterialLookup lookup = materials;
        var settings = CausticaConfig.snapshot();
        int limit = settings.get(MinecraftOptions.Rt.Terrain.COMPLETION_RESULTS_PER_PASS);
        for (int i = 0; i < limit; i++) {
            Build build = completed.poll();
            if (build == null) break;
            outstandingBuilds.decrementAndGet();
            if (build.epoch != epoch || !build.materialEpoch.equals(lookup.epoch())) {
                recordJob(build, "cpu-result-stale-epoch");
                continue;
            }
            if (build.request.section.request != build.request) {
                recordJob(build, "cpu-result-superseded");
                continue;
            }
            if (build.failure != null) throw new IllegalStateException("Terrain extraction failed", build.failure);
            if (build.cpu.mesh() == null) {
                updates.complete(build.request, build);
            } else if (geometry != null) {
                long key = build.request.section.key;
                int x = sectionX(key) << 4, y = sectionY(key) << 4, z = sectionZ(key) << 4;
                var put = new MinecraftTerrainGeometry.Put(key, x, y, z, build.cpu.mesh(),
                        MinecraftTerrainLightAdapter.describe(key, build.revision, x, y, z, build.cpu.lights()));
                recordJob(build, "gpu-prepare-start");
                var preparation = geometry.prepare(put);
                outstandingBuilds.incrementAndGet();
                preparation.whenComplete((mesh, failure) -> {
                    outstandingBuilds.decrementAndGet();
                    var result = new Build(build.request, build.epoch, build.materialEpoch, build.revision,
                            build.cpu, failure, build.extraction, build.ready, mesh);
                    recordJob(result, "gpu-prepare-ready");
                    synchronized (preparationLock) {
                        if (result.epoch != epoch) result.close();
                        else prepared.add(result);
                    }
                });
            }
        }
        for (int i = 0; i < limit; i++) {
            Build build = prepared.poll();
            if (build == null) break;
            if (build.epoch != epoch || build.request.section.request != build.request) {
                recordJob(build, "prepared-result-stale");
                build.close();
                continue;
            }
            if (build.failure != null) throw new IllegalStateException("Terrain mesh preparation failed", build.failure);
            updates.complete(build.request, build);
        }
        publishReady(mc);
        dispatch(mc, lookup, settings);
    }

    private void dispatch(Minecraft mc, MinecraftMaterialLookup lookup,
                          OptionValues settings) {
        int slots = Math.min(settings.get(MinecraftOptions.Rt.Terrain.ASYNC_DISPATCH_PER_PASS),
                settings.get(MinecraftOptions.Rt.Terrain.MAX_INFLIGHT_SECTIONS) - outstandingBuilds.get());
        if (slots <= 0 || updates.pending().isEmpty()) return;
        int cx = mc.player.getBlockX() >> 4, cy = mc.player.getBlockY() >> 4, cz = mc.player.getBlockZ() >> 4;
        Comparator<TerrainUpdates.Request<Build>> order = Comparator
                .comparingInt((TerrainUpdates.Request<Build> request) -> request.section.ready ? 0 : 1)
                .thenComparingLong(request -> distance(request.section.key, cx, cy, cz));
        var candidates = new PriorityQueue<TerrainUpdates.Request<Build>>(slots, order.reversed());
        var readyColumns = new LongOpenHashSet();
        var blockedColumns = new LongOpenHashSet();
        for (var request : updates.pending()) {
            var section = request.section;
            long key = section.key;
            long column = columnKey(sectionX(key), sectionZ(key));
            if (blockedColumns.contains(column)) continue;
            if (!readyColumns.contains(column)) {
                if (!neighborsLoaded(world.getChunkSource(), sectionX(key), sectionZ(key))) {
                    blockedColumns.add(column);
                    continue;
                }
                readyColumns.add(column);
            }
            if (candidates.size() < slots) candidates.add(request);
            else if (order.compare(request, candidates.peek()) < 0) {
                candidates.poll();
                candidates.add(request);
            }
        }
        var ordered = new ArrayList<>(candidates);
        ordered.sort(order);
        for (var request : ordered) dispatch(request, lookup, mc);
    }

    private void dispatch(TerrainUpdates.Request<Build> request, MinecraftMaterialLookup lookup, Minecraft mc) {
        long key = request.section.key;
        int x = sectionX(key), y = sectionY(key), z = sectionZ(key);
        var region = snapshots.createRegion(world, x, y, z);
        var models = mc.getModelManager().getBlockStateModelSet();
        var fluids = mc.getModelManager().getFluidStateModelSet();
        var colors = mc.getBlockColors();
        long taskEpoch = epoch;
        long taskRevision = ++revision;
        Object extraction = instrumentation.extraction(MinecraftTelemetry.GeometrySource.TERRAIN, 1);
        updates.dispatched(request);
        outstandingBuilds.incrementAndGet();
        instrumentation.count("sectionsSnapshotted", 1);
        recordJob(request, taskEpoch, taskRevision, "dispatch", false);
        try {
            workers.submit(() -> {
                try {
                    recordJob(request, taskEpoch, taskRevision, "cpu-start", false);
                    if (taskEpoch != epoch) {
                        recordJob(request, taskEpoch, taskRevision, "cpu-cancelled-epoch", false);
                        completed.add(new Build(request, taskEpoch, lookup.epoch(), taskRevision, null, null, extraction, null));
                        return;
                    }
                    var state = WORKER_TESS.get();
                    state.reset(colors);
                    var cpu = buildCpuSection(region, models, state.blockRandom, state.modelParts,
                            state.capture, fluids, state.fluidCapture, state.mesh, state.pos, lookup, x, y, z);
                    recordJob(request, taskEpoch, taskRevision, "cpu-ready", false);
                    completed.add(new Build(request, taskEpoch, lookup.epoch(), taskRevision, cpu, null,
                            extraction, instrumentation.extraction(MinecraftTelemetry.GeometrySource.TERRAIN_READY, 1)));
                } catch (Throwable failure) {
                    recordJob(request, taskEpoch, taskRevision, "cpu-failed", true);
                    completed.add(new Build(request, taskEpoch, lookup.epoch(), taskRevision, null, failure,
                            extraction, null));
                }
            }, outstandingBuilds::decrementAndGet);
        } catch (RuntimeException | Error failure) {
            outstandingBuilds.decrementAndGet();
            updates.retry(request);
            throw failure;
        }
    }

    private void publishReady(Minecraft mc) {
        if (geometry == null) return;
        int cx = mc.player.getBlockX() >> 4, cy = mc.player.getBlockY() >> 4, cz = mc.player.getBlockZ() >> 4;
        var groups = updates.ready(PUBLICATION_BUDGET, section ->
                (section.ready ? 0L : 1L << 60) + distance(section.key, cx, cy, cz),
                request -> (request.result != null && request.result.prepared != null)
                        || geometry.hasSection(request.section.key));
        if (groups.isEmpty()) return;
        var changes = new ArrayList<MinecraftTerrainGeometry.ReadyChange>();
        for (var group : groups) {
            for (var request : group.requests) {
                Build build = request.result;
                if (build != null && build.prepared != null) changes.add(build.prepared);
                else if (geometry.hasSection(request.section.key))
                    changes.add(new MinecraftTerrainGeometry.Drop(request.section.key));
            }
        }
        if (!changes.isEmpty()) geometry.edit(changes);
        for (var group : groups) {
            for (var request : group.requests) {
                if (request.result != null) {
                    recordJob(request.result, "published");
                    instrumentation.published(request.result.extraction);
                    instrumentation.published(request.result.ready);
                }
            }
        }
        updates.published(groups);
    }

    private void reset() {
        synchronized (preparationLock) {
            epoch++;
            Build result;
            while ((result = prepared.poll()) != null) result.close();
        }
        updates.clear();
        snapshots.clear();
        columns.clear();
        dirty.clear();
        drainDiscardedBuilds();
        if (geometry != null) {
            var drops = geometry.sectionKeys().stream()
                    .map(key -> (MinecraftTerrainGeometry.ReadyChange) new MinecraftTerrainGeometry.Drop(key)).toList();
            if (!drops.isEmpty()) geometry.edit(drops);
        }
    }

    public void shutdown() {
        synchronized (preparationLock) {
            epoch++;
            Build result;
            while ((result = prepared.poll()) != null) result.close();
        }
        workers.shutdown();
        updates.clear();
        drainDiscardedBuilds();
        dirty.clear();
        snapshots.clear();
        columns.clear();
        world = null;
    }

    private void drainDiscardedBuilds() {
        while (completed.poll() != null) outstandingBuilds.decrementAndGet();
    }

    private static boolean neighborsLoaded(ClientChunkCache chunks, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!chunks.hasChunk(x + dx, z + dz)) return false;
            }
        }
        return true;
    }

    private static long distance(long key, int x, int y, int z) {
        long dx = sectionX(key) - x, dz = sectionZ(key) - z;
        return ((dx * dx + dz * dz) << 16) + Math.abs(sectionY(key) - y);
    }

    private static long columnKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    static long sectionKey(int x, int y, int z) {
        return (x & 0x3ffffffL) | ((z & 0x3ffffffL) << 26) | ((y & 0xfffL) << 52);
    }

    private static int sectionX(long key) { return (int) (key << 38 >> 38); }
    private static int sectionY(long key) { return (int) (key >> 52); }
    private static int sectionZ(long key) { return (int) (key << 12 >> 38); }

    private record Build(TerrainUpdates.Request<Build> request, long epoch, ResourcePackEpoch materialEpoch,
                         long revision, RtTerrainMesher.CpuSection cpu, Throwable failure,
                         Object extraction, Object ready, MinecraftTerrainGeometry.Prepared prepared) {
        Build(TerrainUpdates.Request<Build> request, long epoch, ResourcePackEpoch materialEpoch,
              long revision, RtTerrainMesher.CpuSection cpu, Throwable failure, Object extraction, Object ready) {
            this(request, epoch, materialEpoch, revision, cpu, failure, extraction, ready, null);
        }
        void close() { if (prepared != null) prepared.close(); }
    }
}
