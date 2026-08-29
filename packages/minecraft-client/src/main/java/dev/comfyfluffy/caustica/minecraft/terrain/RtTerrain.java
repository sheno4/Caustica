

package dev.comfyfluffy.caustica.minecraft.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.minecraft.light.MinecraftTerrainLightBatch;
import dev.comfyfluffy.caustica.minecraft.light.MinecraftTerrainLightSnapshot;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.minecraft.MinecraftTelemetry;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.WORKER_TESS;
import static dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.buildCpuSection;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.CpuSection;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.WorkerTessState;
/**
 * Per-section terrain residency synced to vanilla's loaded chunks. One root-owned manager keeps a map
 * of resident 16³ sections. The 20 TPS tick maintains the desired window around the player
 * (slid incrementally on section-boundary crossings) and drains dirty events; the actual streaming —
 * snapshot dispatch, completion drain, and publish — runs once per render frame from
 * the RT frame renderer with count-bounded completion and dispatch passes rather than a per-tick burst.
 * Residency follows vanilla because a section is only "desired" when its
 * chunk is loaded ({@code hasChunk}), so chunk load/unload drives build/free without any mixin.
 *
 * <p>Geometry comes from vanilla's baked model path (correct shapes, neighbour cull, biome tint, alpha
 * cutout, and model quad transforms). Vertices are section-local (f32-exact); each placement carries
 * its authored section origin, which the scene manager rebases against the frame origin. The index
 * buffer itself is retained only for the BLAS build (per-triangle corner UVs mean shading never needs
 * an index-buffer read — lever B), so its address isn't duplicated into this table.
 *
 * <p>Tessellation reads only an immutable snapshot ({@link RtSectionSnapshots.Region}, palette-only
 * copies captured on the render thread and cached persistently across passes — see
 * {@link RtSectionSnapshots}). CPU meshing runs on
 * {@link RtWorkerPool}; snapshotting and publication stay on the render thread, while workers produce
 * immutable scene meshes. The scene manager owns all GPU preparation, publication, and retirement.
 */
public final class RtTerrain {
    // The render thread snapshots and publishes; workers only mesh immutable CPU results. The streaming
    // pass is bounded so render-thread bookkeeping stays flat.
    private static int asyncDispatchPerPass() {
        return CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS.value();
    }

    private static int completionResultsPerPass() {
        return CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS.value();
    }

    // Backpressure cap: stop dispatching once this many sections are in flight. Bounds queue depth and
    // snapshot memory (each in-flight region pins 27 cached section snapshots) when flying through the world.
    private static int maxInflight() {
        return CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.value();
    }

    private static final long NO_TESS_TOKEN = Long.MIN_VALUE;
    private static final int NO_MISSING_INDEX = -1;
    private static final long NO_DIRTY_GROUP = 0L;
    // If no render frame has driven a streaming pass for this long, the 20 TPS tick takes over (loading
    // screens / hidden window — states where render-driven streaming has stopped).
    private static final long STREAM_FALLBACK_AFTER_NANOS = 200_000_000L;
    // Light edits and streaming completions can arrive every frame. Coalesce immutable publication inputs
    // without adding noticeable edit latency.
    private static final long LIGHT_SNAPSHOT_UPDATE_INTERVAL_NANOS = 50_000_000L;

    private static int rebaseDistanceBlocks() {
        return CausticaConfig.Rt.Terrain.REBASE_DISTANCE_BLOCKS.value();
    }

    private final RtWorkerPool workers;
    private final MinecraftTelemetry.Instrumentation instrumentation;
    private MinecraftTerrainGeometry retainedGeometry;

    private boolean sceneInitialized;
    private volatile MinecraftMaterialLookup materialLookup;
    // Persistent palette snapshots for tessellation regions (render-thread only); invalidated on dirty
    // sections, column unload/window-leave, and full clears.
    private final RtSectionSnapshots snapshots;
    private final LongOpenHashSet empty = new LongOpenHashSet(); // loaded, in-window sections with no geometry
    private final Object dirtyLock = new Object();
    private final LongOpenHashSet dirty = new LongOpenHashSet(); // edited sections to re-extract
    private final LongArrayList dirtyDrain = new LongArrayList();
    private final ArrayList<DirtyEvent> dirtyEvents = new ArrayList<>();
    private final ArrayList<DirtyEvent> dirtyEventDrain = new ArrayList<>();
    // Persistent desired window and queued work. The expensive section window is rebuilt only when the
    // player crosses a section/radius/Y-band boundary; steady ticks poll chunk columns for load changes.
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet desiredColumns = new LongOpenHashSet();
    private final LongOpenHashSet loadedColumns = new LongOpenHashSet();
    private final LongArrayList missing = new LongArrayList();
    private final Long2IntOpenHashMap missingIndex = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap queuedDirtyGroup = new Long2LongOpenHashMap();
    private final LongArrayList reextract = new LongArrayList();
    private final LongOpenHashSet queuedReextract = new LongOpenHashSet();
    // Published state changes only from the retained-scene publication acknowledgement. Pending markers keep
    // CPU residency coherent while a queued group waits for manager preparation and atomic application.
    private final Long2ObjectOpenHashMap<PublishedSection> publishedSections = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pendingPublications = new LongOpenHashSet();
    private final LongOpenHashSet pendingDrops = new LongOpenHashSet();
    private final Long2LongOpenHashMap pendingPublicationToken = new Long2LongOpenHashMap();
    private final LongOpenHashSet removed = new LongOpenHashSet();
    private final ArrayList<SectionResult> prepared = new ArrayList<>();
    private final ArrayList<PendingGeometryGroup> pendingGeometryGroups = new ArrayList<>();
    private long nextPublicationToken;
    // Worker bookkeeping. `inFlight` maps a dispatched section key to a monotonic token; a completed
    // task whose token no longer matches is discarded.
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap inFlightDirtyGroup = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<DirtyGroup> dirtyGroups = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<SectionResult> completedBuilds = new ConcurrentLinkedQueue<>();
    private final Object activeTaskLock = new Object();
    private int activeTasks;
    /** Invalidates all worker work from a detached world residency without joining it. */
    private volatile long terrainEpoch = 1L;
    private long buildToken;
    private long dirtyGroupSeq;
    // Full-residency invalidation requested off the render thread by the vanilla LevelExtractor hook
    // (dimension change via setLevel, render-distance change, F3+A). Consumed in tick(), where the RT
    // context is available.
    private volatile boolean fullClearRequested;
    private volatile boolean dirtyPending;
    private boolean noWorldClearApplied;
    // Stable frame origin selected from the player position after a distance threshold.
    public int blockX;
    public int blockY;
    public int blockZ;
    /** Sorted light-only snapshot, updated with section publication instead of rescanning all geometry. */
    private final TreeMap<Long, MinecraftTerrainLightBatch> lightSections = new TreeMap<>();
    private long lightGroupRevision;
    private long retainedLightGeneration;
    private boolean lightSnapshotDirty;
    private long lastLightSnapshotNanos;
    private MinecraftTerrainLightSnapshot retainedLights = MinecraftTerrainLightSnapshot.empty(0L);
    private boolean windowValid;
    private int windowPcx;
    private int windowPcz;
    private int windowRadius;
    private int windowLoY;
    private int windowHiY;
    // When the last streaming pass ran on a render frame — the tick fallback watches this (see
    // STREAM_FALLBACK_AFTER_NANOS).
    private long lastFrameStreamNanos;

    private boolean isPublished(long key) {
        return publishedSections.containsKey(key);
    }

    private boolean isPublicationPending(long key) {
        return pendingPublications.contains(key);
    }

    private boolean blocksMissingBuild(long key) {
        return ((isPublished(key) || isPublicationPending(key)) && !pendingDrops.contains(key))
                || empty.contains(key);
    }

    static boolean canRebuildSection(boolean published, boolean pending, boolean empty) {
        return published || pending || empty;
    }

    static boolean requiresDrop(boolean published, boolean pending) {
        return published || pending;
    }

    static boolean discardsCancelledDirtyGroup(long dirtyGroup, boolean groupExists) {
        return dirtyGroup != NO_DIRTY_GROUP && !groupExists;
    }

    static boolean emptyAfterDrop(boolean desired) {
        return desired;
    }

    public RtTerrain(RtWorkerPool workers, MinecraftTelemetry.Instrumentation instrumentation) {
        this.workers = java.util.Objects.requireNonNull(workers, "workers");
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation, "instrumentation");
        snapshots = new RtSectionSnapshots(instrumentation);
        missingIndex.defaultReturnValue(NO_MISSING_INDEX);
        queuedDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
        inFlight.defaultReturnValue(NO_TESS_TOKEN);
        inFlightDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
        pendingPublicationToken.defaultReturnValue(Long.MIN_VALUE);
    }

    /**
     * The manager if it has valid (possibly zero-instance) retained geometry state to trace against, else null.
     * Null only while genuinely uninitialized (no world, or mid-teardown) — a transient empty-residency
     * window (world join, dimension change, a full evict) still returns non-null so the RT frame keeps
     * tracing (sky/entities only) instead of a caller falling back to vanilla.
     */
    public RtTerrain currentOrNull() {
        return sceneInitialized ? this : null;
    }

    public boolean isSectionReady(BlockPos blockPos) {
        int scx = SectionPos.blockToSectionCoord(blockPos.getX());
        int scy = SectionPos.blockToSectionCoord(blockPos.getY());
        int scz = SectionPos.blockToSectionCoord(blockPos.getZ());
        long key = sectionKey(scx, scy, scz);
        return sceneInitialized && (isPublished(key) || empty.contains(key));
    }

    public MinecraftTerrainLightSnapshot retainedLightSnapshot() {
        return retainedLights;
    }

    /** Stable renderer frame origin selected by the terrain streaming window. */
    public SceneOrigin sceneOrigin() {
        return new SceneOrigin(blockX, blockY, blockZ);
    }

    /** Per-tick residency update: window sync + dirty drain (plus the streaming fallback, see {@link #frame}). */
    public void update() {
        tick();
    }

    /**
     * Per-render-frame streaming pass driven by the RT frame renderer: publish completed builds
     * and dispatch immutable snapshots to workers, bounded by configured per-pass counts.
     */
    public void frame() {
        instrumentation.max("terrainPendingGeometryGroups", pendingGeometryGroups.size());
        if (materialLookup != null) frameStream();
        submitPendingGeometry();
    }

    public void bindGeometry(MinecraftTerrainGeometry geometry) {
        if (retainedGeometry != null) throw new IllegalStateException("terrain geometry is already bound");
        retainedGeometry = java.util.Objects.requireNonNull(geometry, "geometry");
    }

    public void unbindGeometry(MinecraftTerrainGeometry geometry) {
        if (retainedGeometry == geometry) retainedGeometry = null;
    }

    /** Installs the immutable material lookup used by subsequently dispatched section builds. */
    public void publishMaterialLookup(MinecraftMaterialLookup lookup) {
        materialLookup = java.util.Objects.requireNonNull(lookup, "lookup");
        fullClearRequested = true;
    }

    public void clearMaterialLookup() {
        materialLookup = null;
    }

    public void shutdown() {
        clear(true);
        sceneInitialized = false;
    }

    /**
     * Mark every section overlapping a dirty block area — <em>plus the bordering neighbour sections</em>
     * — for re-extraction. Fed by the LevelExtractor hook (vanilla's block-change signal). Thread-safe;
     * drained on the next {@link #tick}.
     *
     * <p>The block area is expanded by one block on every side before mapping to sections, matching
     * vanilla's own dirty expansion. A change touching a section edge therefore also re-extracts the
     * adjacent section: that neighbour's cull faces toward the change (a broken block uncovers a face)
     * and, for fluids, its shared-edge surface heights (the top-face corner heights are averaged from
     * the blocks straddling the section boundary) both depend on the edited block. Without re-extracting
     * it the neighbour keeps stale geometry — opaque holes and a disconnected water surface at the seam.
     * Interior edits stay within one section (±1 doesn't cross a 16-block boundary).
     */
    public void markBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        synchronized (dirtyLock) {
            LongArrayList keys = new LongArrayList();
            for (int scx = (minX - 1) >> 4; scx <= (maxX + 1) >> 4; scx++) {
                for (int scy = (minY - 1) >> 4; scy <= (maxY + 1) >> 4; scy++) {
                    for (int scz = (minZ - 1) >> 4; scz <= (maxZ + 1) >> 4; scz++) {
                        keys.add(sectionKey(scx, scy, scz));
                    }
                }
            }
            if (!keys.isEmpty()) {
                long groupId = ++dirtyGroupSeq;
                if (groupId == NO_DIRTY_GROUP) {
                    groupId = ++dirtyGroupSeq;
                }
                dirtyEvents.add(new DirtyEvent(groupId, keys));
                dirtyPending = true;
            }
        }
    }

    /**
     * Request a full residency clear, applied on the next {@link #tick} on the render thread. Wired to vanilla's
     * {@link net.minecraft.client.renderer.extract.LevelExtractor#allChanged()}, which fires on a
     * dimension change (via {@code setLevel}), a render-distance change, and F3+A. Thread-safe.
     */
    public void requestFullClear() {
        fullClearRequested = true;
    }

    private void tick() {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            sceneInitialized = false;
            if (!noWorldClearApplied) {
                clear(false);
                noWorldClearApplied = true;
            }
            return;
        }
        sceneInitialized = true;
        noWorldClearApplied = false;
        if (materialLookup == null) {
            return; // resource reload gap: publication resumes after an immutable lookup is installed
        }

        // Full clear on an explicit invalidation — vanilla's LevelExtractor.allChanged(). That fires on a
        // dimension switch (setLevel → allChanged),
        // render-distance change, and F3+A. Without it, End→Overworld keeps the old dimension's geometry:
        // residency is keyed by raw section coords (no world identity), so the same coords stay resident
        // and are never rebuilt for the new world.
        if (fullClearRequested) {
            fullClearRequested = false;
            clear(false);
        }

        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;
        int r = horizontalChunks(mc);
        ClientChunkCache chunkSource = level.getChunkSource();
        int minSecY = level.getMinY() >> 4;
        int maxSecY = (level.getMinY() + level.getHeight() - 1) >> 4;
        int loY = minSecY;
        int hiY = maxSecY;

        // Evicted geometry lands in `removed` and is consumed by the next streaming pass's build kick.
        long windowSyncStart = instrumentation.startStage();
        try {
            syncDesiredWindow(chunkSource, pcx, psy, pcz, r, loY, hiY, removed);
        } finally {
            instrumentation.endStage("terrain.windowSync", windowSyncStart);
        }

        // Re-extract edited sections. Drain under a short lock so concurrent block updates are not lost.
        long dirtyDrainStart = instrumentation.startStage();
        try {
            drainDirty();
            if (!dirtyDrain.isEmpty()) {
                for (LongIterator it = dirtyDrain.iterator(); it.hasNext(); ) {
                    long key = it.nextLong();
                    handleDirtySection(key, NO_DIRTY_GROUP);
                }
            }
            if (!dirtyEventDrain.isEmpty()) {
                for (DirtyEvent event : dirtyEventDrain) {
                    handleDirtyEvent(event);
                }
            }
        } finally {
            instrumentation.endStage("terrain.dirtyDrain", dirtyDrainStart);
        }
        // Dispatch/drain/build normally runs per render frame. If no frame has streamed recently — startup,
        // loading screen, or a hidden window — drive and publish the same bounded work from the tick. Startup
        // cannot wait for frame-driven publication because frame capture begins only after a nearby section is
        // published.
        if (System.nanoTime() - lastFrameStreamNanos > STREAM_FALLBACK_AFTER_NANOS) {
            stream();
            submitPendingGeometry();
        }
    }

    /** The per-render-frame entry point: run one count-bounded streaming pass. */
    private void frameStream() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        lastFrameStreamNanos = System.nanoTime();
        stream();
    }

    /**
     * One streaming pass: drain completed CPU builds, submit ready sections, and dispatch
     * new section snapshots to the worker pool. Per-pass result and dispatch caps bound render-thread
     * work. Skips silently when there is nothing to do (no stats row).
     */
    private void stream() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (reextract.isEmpty() && missing.isEmpty()
                && completedBuilds.isEmpty()
                && !lightSnapshotDirty
                && removed.isEmpty() && prepared.isEmpty()) {
            return;
        }
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;

        ClientChunkCache chunkSource = level.getChunkSource();

        // Drain completed CPU builds first — publication is visible fill progress, so it gets priority.
        long completionStart = instrumentation.startStage();
        try {
            drainCompletedBuilds(prepared, removed, completionResultsPerPass());
        } finally {
            instrumentation.endStage("terrain.drainCompletion", completionStart);
        }

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            long publishStart = instrumentation.startStage();
            try {
                applyBuildChanges(prepared, removed, shouldRebase(pbx, pby, pbz), pbx, pby, pbz);
                removed.clear();
                prepared.clear();
            } finally {
                instrumentation.endStage("terrain.publish", publishStart);
            }
        }

        // Snapshot and dispatch a bounded number of new worker-owned section builds.
        long snapshotDispatchStart = instrumentation.startStage();
        try {
            DispatchContext dispatch = null;
            int dispatchSlots = Math.min(asyncDispatchPerPass(), Math.max(0, maxInflight() - inFlight.size()));
            if (dispatchSlots > 0 && !reextract.isEmpty()) {
                if (dispatch == null) {
                    dispatch = dispatchContext(level);
                }
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, pcx, psy, pcz);
            }
            if (dispatchSlots > 0 && !missing.isEmpty()) {
                if (dispatch == null) {
                    dispatch = dispatchContext(level);
                }
                dispatchMissingBuilds(dispatch, chunkSource, dispatchSlots, pcx, psy, pcz);
            }
        } finally {
            instrumentation.endStage("terrain.snapshotDispatch", snapshotDispatchStart);
        }

        flushLightSnapshotUpdate();

    }

    private void syncDesiredWindow(ClientChunkCache chunkSource, int pcx, int psy, int pcz,
                                   int radius, int loY, int hiY, LongOpenHashSet removed) {
        if (!windowValid || windowRadius != radius || windowLoY != loY || windowHiY != hiY
                || Math.abs(pcx - windowPcx) > radius || Math.abs(pcz - windowPcz) > radius) {
            // First window, a shape change, or a jump past any overlap (teleport) — build from scratch.
            rebuildDesiredWindow(chunkSource, pcx, pcz, radius, loY, hiY, removed);
        } else if (windowPcx != pcx || windowPcz != pcz) {
            slideDesiredWindow(chunkSource, pcx, pcz, radius, loY, hiY, removed);
        } else {
            pollLoadedColumns(chunkSource, loY, hiY, removed);
        }
    }

    private void rebuildDesiredWindow(ClientChunkCache chunkSource, int pcx, int pcz,
                                      int radius, int loY, int hiY, LongOpenHashSet removed) {
        snapshots.clear(); // teleport / shape change — no per-column eviction diff, drop everything
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        missingIndex.clear();

        for (int scx = pcx - radius; scx <= pcx + radius; scx++) {
            for (int scz = pcz - radius; scz <= pcz + radius; scz++) {
                long column = columnKey(scx, scz);
                desiredColumns.add(column);
                if (!chunkSource.hasChunk(scx, scz)) {
                    continue;
                }
                loadedColumns.add(column);
                addDesiredColumnSections(scx, scz, loY, hiY);
            }
        }

        pruneUndesired(removed);
        removeKeysNotIn(queuedReextract, desired);
        windowValid = true;
        windowPcx = pcx;
        windowPcz = pcz;
        windowRadius = radius;
        windowLoY = loY;
        windowHiY = hiY;
    }

    /**
     * Slide the desired window after a section-boundary crossing: touch only the columns entering and
     * leaving the (2r+1)² rect instead of rebuilding it (the rebuild — ~100k hash inserts + a full
     * resident prune + a queue sort at r=32 — was a 10–30 ms hitch every 16 blocks of flight).
     */
    private void slideDesiredWindow(ClientChunkCache chunkSource, int pcx, int pcz,
                                    int radius, int loY, int hiY, LongOpenHashSet removed) {
        int newMinX = pcx - radius, newMaxX = pcx + radius;
        int newMinZ = pcz - radius, newMaxZ = pcz + radius;
        for (int scx = windowPcx - radius; scx <= windowPcx + radius; scx++) {
            boolean xOutside = scx < newMinX || scx > newMaxX;
            for (int scz = windowPcz - radius; scz <= windowPcz + radius; scz++) {
                if (!xOutside && scz >= newMinZ && scz <= newMaxZ) {
                    continue; // still in the window
                }
                long column = columnKey(scx, scz);
                desiredColumns.remove(column);
                // Only loaded columns ever had desired sections / queued work (see addDesiredColumnSections
                // call sites) — nothing to remove for a never-loaded column.
                if (loadedColumns.remove(column)) {
                    removeDesiredColumnSections(scx, scz, loY, hiY, removed);
                }
            }
        }
        int oldMinX = windowPcx - radius, oldMaxX = windowPcx + radius;
        int oldMinZ = windowPcz - radius, oldMaxZ = windowPcz + radius;
        for (int scx = newMinX; scx <= newMaxX; scx++) {
            boolean xOutside = scx < oldMinX || scx > oldMaxX;
            for (int scz = newMinZ; scz <= newMaxZ; scz++) {
                if (!xOutside && scz >= oldMinZ && scz <= oldMaxZ) {
                    continue;
                }
                long column = columnKey(scx, scz);
                desiredColumns.add(column);
                if (chunkSource.hasChunk(scx, scz)) {
                    loadedColumns.add(column);
                    addDesiredColumnSections(scx, scz, loY, hiY);
                }
            }
        }
        windowPcx = pcx;
        windowPcz = pcz;
    }

    private void pollLoadedColumns(ClientChunkCache chunkSource, int loY, int hiY, LongOpenHashSet removed) {
        for (LongIterator it = desiredColumns.iterator(); it.hasNext(); ) {
            long column = it.nextLong();
            int scx = columnX(column);
            int scz = columnZ(column);
            boolean loaded = chunkSource.hasChunk(scx, scz);
            boolean wasLoaded = loadedColumns.contains(column);
            if (loaded == wasLoaded) {
                continue;
            }
            if (loaded) {
                loadedColumns.add(column);
                addDesiredColumnSections(scx, scz, loY, hiY);
            } else {
                loadedColumns.remove(column);
                removeDesiredColumnSections(scx, scz, loY, hiY, removed);
            }
        }
    }

    private void addDesiredColumnSections(int scx, int scz, int loY, int hiY) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            desired.add(key);
            enqueueMissingIfNeeded(key);
        }
    }

    private void removeDesiredColumnSections(int scx, int scz, int loY, int hiY, LongOpenHashSet removed) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            // Unloaded or out of the window — the chunk may reload with different data, so the cached
            // snapshot can't be trusted past this point.
            snapshots.invalidate(key);
            desired.remove(key);
            clearQueuedWork(key, true);
            invalidateInFlight(key);
            empty.remove(key);
            if (requiresDrop(isPublished(key), isPublicationPending(key))) removed.add(key);
        }
    }

    private void pruneUndesired(LongOpenHashSet removed) {
        for (LongIterator it = publishedSections.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!desired.contains(key) && !pendingDrops.contains(key)) removed.add(key);
        }
        for (LongIterator it = pendingPublications.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!desired.contains(key) && !pendingDrops.contains(key)) removed.add(key);
        }
        removeKeysNotIn(empty, desired);
        removeInFlightNotIn(desired);
        removeQueuedGroupsNotIn(desired);
    }

    private void handleDirtyEvent(DirtyEvent event) {
        int groupMembers = 0;
        for (LongIterator it = event.keys().iterator(); it.hasNext(); ) {
            if (canGroupDirtySection(it.nextLong())) {
                groupMembers++;
            }
        }

        long groupId = NO_DIRTY_GROUP;
        if (groupMembers > 1) {
            groupId = event.groupId();
            dirtyGroups.put(groupId, new DirtyGroup(groupId, groupMembers, event.keys()));
        }

        for (LongIterator it = event.keys().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            long memberGroup = groupId != NO_DIRTY_GROUP
                    && dirtyGroups.containsKey(groupId)
                    && canGroupDirtySection(key) ? groupId : NO_DIRTY_GROUP;
            if (!handleDirtySection(key, memberGroup) && memberGroup != NO_DIRTY_GROUP) {
                cancelDirtyGroup(memberGroup);
            }
        }
    }

    private boolean canGroupDirtySection(long key) {
        return desired.contains(key) && canRebuildSection(isPublished(key), isPublicationPending(key), empty.contains(key));
    }

    private boolean handleDirtySection(long key, long dirtyGroup) {
        snapshots.invalidate(key); // block data changed — the cached palette snapshot is stale
        invalidateInFlight(key); // invalidate any in-flight build of the now-stale section
        if (!desired.contains(key)) {
            clearQueuedWork(key, true);
            return false;
        }
        // Keep the old geometry resident + traced; re-dispatch and swap when the new mesh is ready
        // (no eviction gap -> no flicker). Non-resident dirty keys re-enter the normal missing queue.
        if (canRebuildSection(isPublished(key), isPublicationPending(key), empty.contains(key))) {
            if (queuedReextract.add(key)) {
                reextract.add(key);
            }
            setQueuedGroup(key, dirtyGroup);
            return true;
        } else {
            return enqueueMissing(key, dirtyGroup);
        }
    }

    private boolean enqueueMissingIfNeeded(long key) {
        if (blocksMissingBuild(key) || inFlight.containsKey(key)) {
            return false;
        }
        if (missingIndex.get(key) != NO_MISSING_INDEX) {
            return false;
        }
        setQueuedGroup(key, NO_DIRTY_GROUP);
        missingIndex.put(key, missing.size());
        missing.add(key);
        return true;
    }

    private boolean enqueueMissing(long key, long dirtyGroup) {
        if (blocksMissingBuild(key) || inFlight.containsKey(key)) {
            return false;
        }
        // `missing` is unsorted; dispatch ranks it directly by distance from the player.
        int index = missingIndex.get(key);
        if (index == NO_MISSING_INDEX) {
            missingIndex.put(key, missing.size());
            missing.add(key);
        }
        setQueuedGroup(key, dirtyGroup);
        return true;
    }

    private void clearQueuedWork(long key, boolean cancelGroup) {
        removeMissing(key);
        queuedReextract.remove(key);
        clearQueuedGroup(key, cancelGroup);
    }

    private void setQueuedGroup(long key, long groupId) {
        long oldGroup = groupId == NO_DIRTY_GROUP ? queuedDirtyGroup.remove(key) : queuedDirtyGroup.put(key, groupId);
        if (oldGroup != NO_DIRTY_GROUP && oldGroup != groupId) {
            cancelDirtyGroup(oldGroup);
        }
    }

    private void clearQueuedGroup(long key, boolean cancelGroup) {
        long groupId = queuedDirtyGroup.remove(key);
        if (cancelGroup && groupId != NO_DIRTY_GROUP) {
            cancelDirtyGroup(groupId);
        }
    }

    private boolean isQueuedAnywhere(long key) {
        return missingIndex.get(key) != NO_MISSING_INDEX
                || queuedReextract.contains(key);
    }

    /** Remove an unsorted missing entry in O(1) by moving the last entry into its slot. */
    private void removeMissing(long key) {
        int index = missingIndex.remove(key);
        if (index == NO_MISSING_INDEX) {
            return;
        }
        int lastIndex = missing.size() - 1;
        if (index != lastIndex) {
            long movedKey = missing.getLong(lastIndex);
            missing.set(index, movedKey);
            missingIndex.put(movedKey, index);
        }
        missing.removeLong(lastIndex);
    }

    private void invalidateInFlight(long key) {
        long token = inFlight.remove(key);
        long groupId = inFlightDirtyGroup.remove(key);
        if (token != NO_TESS_TOKEN && groupId != NO_DIRTY_GROUP) {
            cancelDirtyGroup(groupId);
        }
    }

    private void removeInFlightNotIn(LongOpenHashSet keep) {
        for (LongIterator it = inFlight.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!keep.contains(key)) {
                it.remove();
                long groupId = inFlightDirtyGroup.remove(key);
                if (groupId != NO_DIRTY_GROUP) {
                    cancelDirtyGroup(groupId);
                }
            }
        }
    }

    private void removeQueuedGroupsNotIn(LongOpenHashSet keep) {
        LongArrayList cancelGroups = new LongArrayList();
        for (LongIterator it = queuedDirtyGroup.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!keep.contains(key)) {
                long groupId = queuedDirtyGroup.get(key);
                it.remove();
                if (groupId != NO_DIRTY_GROUP) {
                    cancelGroups.add(groupId);
                }
            }
        }
        for (LongIterator it = cancelGroups.iterator(); it.hasNext(); ) {
            cancelDirtyGroup(it.nextLong());
        }
    }

    /**
     * Whether a section may be built now: all eight of its horizontal neighbour chunks are loaded. We
     * extract using vanilla's model/fluid renderers, which read across chunk borders for cull faces and
     * (for fluids) the surrounding blocks that set a water surface's edge/corner heights. If a border
     * section is built while a neighbour chunk is still missing, those reads return air — the
     * neighbour-facing faces and the shared water surface come out wrong, and nothing re-dirties the
     * section once the chunk arrives (a bulk chunk load fires no per-block update). Deferring the build
     * until every neighbour is present makes the first build correct.
     *
     * <p>We deliberately gate on <em>all</em> neighbours, not just those inside the RT view window. A
     * section at the window edge can have an outward neighbour that is outside the current vanilla-loaded
     * area. Without this, the edge section would mesh against air, then show a seam once the player moves
     * and that neighbour becomes interior and is rendered.
     */
    private boolean neighborChunksReady(ClientChunkCache chunkSource, int scx, int scz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!chunkSource.hasChunk(scx + dx, scz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int horizontalChunks(Minecraft mc) {
        return Math.max(1, mc.options.getEffectiveRenderDistance());
    }

    private void drainDirty() {
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        if (!dirtyPending) {
            return;
        }
        synchronized (dirtyLock) {
            for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
                dirtyDrain.add(it.nextLong());
            }
            dirtyEventDrain.addAll(dirtyEvents);
            dirty.clear();
            dirtyEvents.clear();
            dirtyPending = false;
        }
    }

    private static void removeKeysNotIn(LongSet keys, LongOpenHashSet keep) {
        for (LongIterator it = keys.iterator(); it.hasNext(); ) {
            if (!keep.contains(it.nextLong())) {
                it.remove();
            }
        }
    }

    /**
     * Snapshot each missing section on the render thread and submit its build to the worker pool. The
     * per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the task so
     * nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped by the configured dispatch count.
     */
    private static DispatchContext dispatchContext(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        return new DispatchContext(level,
                mc.getModelManager().getBlockStateModelSet(), mc.getModelManager().getFluidStateModelSet(),
                mc.getBlockColors());
    }

    /**
     * Dispatch the best missing sections. {@code missing} is an indexed unsorted queue; every call scans
     * its contiguous key array while collecting the top candidates by
     * (column-distance², |Δy|) into a bounded
     * max-heap — nearest-first order continuously tracks the player with no sort anywhere (the old
     * sorted-queue approach re-sorted the whole list on every window rebuild, a multi-ms spike at high
     * render distance). Ranking by <b>column</b> first makes the dispatch order column-coherent: all
     * sections of a column share the same 3×3 chunk neighbourhood, and the pass-scoped
     * {@link RenderRegionCache} dedupes {@code SectionCopy}s, so after a column's first snapshot the rest
     * are nearly free.
     */
    private void dispatchMissingBuilds(DispatchContext dispatch, ClientChunkCache chunkSource, int remaining,
                                       int pcx, int psy, int pcz) {
        if (missing.isEmpty() || remaining <= 0) {
            return;
        }
        // Over-collect 2x the remaining slots so candidates skipped for unready neighbour chunks (they cluster at
        // the window edge) don't leave dispatch slots idle.
        int k = Math.min(missing.size(), Math.max(8, remaining * 2));

        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        for (int read = 0, n = missing.size(); read < n; read++) {
            long key = missing.getLong(read);
            // rank = columnDist²(16+) | |Δy|(0..15): column-major nearest-first.
            long rank = distanceRank(key, pcx, psy, pcz);
            if (heapSize < k) {
                heapRank[heapSize] = rank;
                heapKey[heapSize] = key;
                siftUp(heapRank, heapKey, heapSize++);
            } else if (rank < heapRank[0]) {
                heapRank[0] = rank;
                heapKey[0] = key;
                siftDown(heapRank, heapKey, heapSize, 0);
            }
        }
        // Heapsort the candidates ascending (best first), then dispatch up to the per-pass cap.
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        for (int i = 0; i < heapSize && remaining > 0; i++) {
            long key = heapKey[i];
            if (!desired.contains(key) || blocksMissingBuild(key) || inFlight.containsKey(key)) {
                removeMissing(key);
                clearQueuedGroup(key, true);
                continue;
            }
            int sx = sectionX(key);
            int sz = sectionZ(key);
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                continue; // stays queued; dispatched once the neighbours load
            }
            removeMissing(key);
            remaining--;
            dispatchSectionBuild(dispatch, key, sx, sectionY(key), sz);
        }
    }

    /** Max-heap sift-up on parallel (rank, key) arrays — worst candidate at the root. */
    private static void siftUp(long[] rank, long[] key, int i) {
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (rank[parent] >= rank[i]) {
                return;
            }
            long r = rank[parent]; rank[parent] = rank[i]; rank[i] = r;
            long q = key[parent]; key[parent] = key[i]; key[i] = q;
            i = parent;
        }
    }

    private static void siftDown(long[] rank, long[] key, int size, int i) {
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int big = i;
            if (left < size && rank[left] > rank[big]) {
                big = left;
            }
            if (right < size && rank[right] > rank[big]) {
                big = right;
            }
            if (big == i) {
                return;
            }
            long r = rank[big]; rank[big] = rank[i]; rank[i] = r;
            long q = key[big]; key[big] = key[i]; key[i] = q;
            i = big;
        }
    }

    /**
     * Re-extraction of edited (dirty) sections that are still resident: dispatch a fresh
     * rebuild while leaving the old geometry resident and traced, so it's swapped — never evicted
     * with a gap — when the new mesh is published and the replaced geometry is retired. This
     * is what prevents the visible flicker on block updates that plain eviction would cause.
     */
    private int dispatchReextract(DispatchContext dispatch, ClientChunkCache chunkSource, int remaining,
                                  int pcx, int psy, int pcz) {
        if (reextract.isEmpty()) {
            return 0;
        }
        int k = Math.min(reextract.size(), Math.max(8, remaining * 2));
        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        for (int i = 0; i < reextract.size(); ) {
            long key = reextract.getLong(i);
            if (!queuedReextract.contains(key)) {
                if (!isQueuedAnywhere(key)) {
                    clearQueuedGroup(key, true);
                }
                removeUnsorted(reextract, i);
                continue;
            }
            // Skip ones the window pass freed this tick (out of view) — they're being retired, not rebuilt.
            if (!canRebuildSection(isPublished(key), isPublicationPending(key), empty.contains(key))
                    || !desired.contains(key) || inFlight.containsKey(key)) {
                queuedReextract.remove(key);
                clearQueuedGroup(key, true);
                removeUnsorted(reextract, i);
                continue;
            }
            int sx = sectionX(key);
            int sz = sectionZ(key);
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                i++;
                continue;
            }
            long rank = distanceRank(key, pcx, psy, pcz);
            if (heapSize < k) {
                heapRank[heapSize] = rank;
                heapKey[heapSize] = key;
                siftUp(heapRank, heapKey, heapSize++);
            } else if (rank < heapRank[0]) {
                heapRank[0] = rank;
                heapKey[0] = key;
                siftDown(heapRank, heapKey, heapSize, 0);
            }
            i++;
        }
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        int dispatched = 0;
        for (int i = 0; i < heapSize && remaining > 0; i++) {
            long key = heapKey[i];
            queuedReextract.remove(key);
            removeUnsorted(reextract, reextract.indexOf(key));
            dispatchSectionBuild(dispatch, key,
                    sectionX(key), sectionY(key), sectionZ(key));
            remaining--;
            dispatched++;
        }
        return dispatched;
    }

    private static long distanceRank(long key, int pcx, int psy, int pcz) {
        int dx = sectionX(key) - pcx;
        int dz = sectionZ(key) - pcz;
        long colDist2 = (long) dx * dx + (long) dz * dz;
        long dy = Math.min(0xFFFF, Math.abs(sectionY(key) - psy));
        return (colDist2 << 16) | dy;
    }

    private static void removeUnsorted(LongArrayList queue, int index) {
        int last = queue.size() - 1;
        if (index != last) {
            queue.set(index, queue.getLong(last));
        }
        queue.removeLong(last);
    }

    /** Snapshot one section and dispatch CPU-only meshing to the worker pool. */
    private void dispatchSectionBuild(DispatchContext dispatch, long key, int sx, int sy, int sz) {
        instrumentation.count("sectionsSnapshotted", 1);
        RtSectionSnapshots.Region region = snapshots.createRegion(dispatch.level(), sx, sy, sz);
        long token = ++buildToken;
        long dirtyGroup = queuedDirtyGroup.remove(key);
        if (dirtyGroup != NO_DIRTY_GROUP && !dirtyGroups.containsKey(dirtyGroup)) {
            dirtyGroup = NO_DIRTY_GROUP;
        }
        MinecraftMaterialLookup lookup = materialLookup;
        if (lookup == null) return;
        SectionTask task = new SectionTask(key, token, sx << 4, sy << 4, sz << 4, dirtyGroup,
                terrainEpoch, lookup.epoch(),
                instrumentation.extraction(MinecraftTelemetry.GeometrySource.TERRAIN, 1));
        beginActiveTask();
        try {
            workers.submit(() -> {
                try {
                    if (!isTaskCurrent(task)) {
                        completeEmptyTask(task);
                        return;
                    }
                    WorkerTessState ws = WORKER_TESS.get(); // thread-confined; reset per task, arrays amortized
                    ws.reset(dispatch.blockColors());
                    CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), ws.blockRandom, ws.modelParts,
                            ws.capture, dispatch.fluidModelSet(), ws.fluidCapture, ws.mesh, ws.pos,
                            lookup, sx, sy, sz);
                    if (!isTaskCurrent(task)) {
                        completeEmptyTask(task);
                        return;
                    }
                    MinecraftTerrainMesh mesh = cpu.mesh();
                    if (mesh == null) {
                        completeEmptyTask(task);
                    } else {
                        completeTask(new SectionResult(task, mesh, cpu.lights(), null));
                    }
                } catch (Throwable t) {
                    completeTask(task, t);
                    throw t;
                }
            }, () -> completeEmptyTask(task));
        } catch (Throwable t) {
            finishActiveTask();
            throw t;
        }
        inFlight.put(key, token);
        if (dirtyGroup != NO_DIRTY_GROUP) {
            inFlightDirtyGroup.put(key, dirtyGroup);
        } else {
            inFlightDirtyGroup.remove(key);
        }
    }

    private void completeEmptyTask(SectionTask task) {
        completeTask(new SectionResult(task, null, null, null));
    }

    private void completeTask(SectionTask task, Throwable failure) {
        completeTask(new SectionResult(task, null, null, failure));
    }

    private void completeTask(SectionResult result) {
        try {
            if (isTaskCurrent(result.task())) {
                completedBuilds.add(result.withWorkerCompletion(System.nanoTime(),
                        instrumentation.extraction(MinecraftTelemetry.GeometrySource.TERRAIN_READY, 1)));
            }
        } finally {
            finishActiveTask();
        }
    }

    private boolean isTaskCurrent(SectionTask task) {
        return task.terrainEpoch == terrainEpoch;
    }

    private void beginActiveTask() {
        synchronized (activeTaskLock) {
            activeTasks++;
        }
    }

    private void finishActiveTask() {
        synchronized (activeTaskLock) {
            if (--activeTasks < 0) {
                throw new IllegalStateException("RT terrain active-task underflow");
            }
            if (activeTasks == 0) {
                activeTaskLock.notifyAll();
            }
        }
    }

    private void awaitActiveTasks() {
        synchronized (activeTaskLock) {
            while (activeTasks != 0) {
                try {
                    activeTaskLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while joining RT terrain tasks", e);
                }
            }
        }
    }

    /**
     * Publish terminal worker/executor results (up to the configured result count per pass). A task
     * whose token no longer matches {@link #inFlight} is stale and discarded instead of entering the
     * retained-scene submission queue.
     */
    private void drainCompletedBuilds(List<SectionResult> prepared, LongOpenHashSet removed,
                                      int resultCap) {
        int remaining = resultCap;
        while (remaining > 0) {
            SectionResult result = completedBuilds.poll();
            if (result == null) {
                break;
            }
            SectionTask task = result.task();
            long expected = inFlight.get(task.key);
            boolean tokenValid = expected == task.token;
            boolean valid = tokenValid && task.terrainEpoch == terrainEpoch
                    && materialLookup != null && task.materialEpoch.equals(materialLookup.epoch());
            if (!valid) {
                if (tokenValid) {
                    inFlight.remove(task.key);
                    inFlightDirtyGroup.remove(task.key);
                    long staleGroup = task.dirtyGroup;
                    if (staleGroup != NO_DIRTY_GROUP) cancelDirtyGroup(staleGroup);
                    enqueueMissingIfNeeded(task.key);
                    instrumentation.count("terrainMaterialEpochRejects", 1);
                }
                continue;
            }
            inFlight.remove(task.key);
            inFlightDirtyGroup.remove(task.key);
            long dirtyGroup = task.dirtyGroup;
            if (result.failure() != null) {
                if (dirtyGroup != NO_DIRTY_GROUP) {
                    cancelDirtyGroup(dirtyGroup);
                }
                throw new RuntimeException("RT terrain section build failed for section "
                        + (task.sox >> 4) + "," + (task.soy >> 4) + "," + (task.soz >> 4),
                        result.failure());
            }
            if (discardsCancelledDirtyGroup(dirtyGroup, dirtyGroups.containsKey(dirtyGroup))) {
                // A cancelled dirty group must never publish a surviving member as a singleton.
                remaining--;
                continue;
            }
            if (dirtyGroup != NO_DIRTY_GROUP) {
                DirtyGroup group = dirtyGroups.get(dirtyGroup);
                if (result.geometry() == null) {
                    group.removed.add(task.key);
                } else {
                    group.prepared.add(result);
                }
                completeDirtyGroupMember(group);
                remaining--;
            } else {
                if (result.geometry() == null) {
                    // Legitimately empty (air or fully-enclosed). If this was an in-place re-extract whose new
                    // state is empty, evict the old geom and retire it in this publish pass.
                    removed.add(task.key);
                    remaining--;
                } else {
                    prepared.add(result);
                    remaining--;
                }
            }
        }
    }

    private void completeDirtyGroupMember(DirtyGroup group) {
        if (--group.remaining > 0) {
            return;
        }
        dirtyGroups.remove(group.id);
        submitDirtyGroup(group);
    }

    private void cancelDirtyGroup(long groupId) {
        DirtyGroup group = dirtyGroups.remove(groupId);
        if (group == null) {
            return;
        }
        for (LongIterator it = group.keys.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (queuedDirtyGroup.get(key) == groupId) {
                queuedDirtyGroup.remove(key);
            }
            if (inFlightDirtyGroup.get(key) == groupId) {
                inFlightDirtyGroup.remove(key);
            }
        }
    }

    private void cancelAllDirtyGroups() {
        if (dirtyGroups.isEmpty()) {
            return;
        }
        dirtyGroups.clear();
        queuedDirtyGroup.clear();
        inFlightDirtyGroup.clear();
    }


    /** Per-tick render-thread snapshot dependencies shared by reextract + missing dispatch. */
    private record DispatchContext(ClientLevel level, BlockStateModelSet modelSet,
                                   FluidStateModelSet fluidModelSet, BlockColors blockColors) {
    }

    private record DirtyEvent(long groupId, LongArrayList keys) {
    }

    private static final class DirtyGroup {
        final long id;
        final LongArrayList keys;
        final ArrayList<SectionResult> prepared = new ArrayList<>();
        final LongOpenHashSet removed = new LongOpenHashSet();
        int remaining;

        DirtyGroup(long id, int remaining, LongArrayList keys) {
            this.id = id;
            this.remaining = remaining;
            this.keys = new LongArrayList(keys);
        }
    }

    /** An outstanding CPU section build; terminal results enter one completion queue. */
    private static final class SectionTask {
        final long key;
        final long token;
        final int sox;
        final int soy;
        final int soz;
        final long dirtyGroup;
        final long terrainEpoch;
        final ResourcePackEpoch materialEpoch;
        final Object extractionStamp;
        SectionTask(long key, long token, int sox, int soy, int soz, long dirtyGroup,
                    long terrainEpoch, ResourcePackEpoch materialEpoch,
                    Object extractionStamp) {
            this.key = key;
            this.token = token;
            this.sox = sox;
            this.soy = soy;
            this.soz = soz;
            this.dirtyGroup = dirtyGroup;
            this.terrainEpoch = terrainEpoch;
            this.materialEpoch = materialEpoch;
            this.extractionStamp = extractionStamp;
        }
    }

    private record SectionResult(SectionTask task, MinecraftTerrainMesh geometry, float[] lights, Throwable failure,
                                 long workerCompletedNanos,
                                 Object readyStamp) {
        SectionResult(SectionTask task, MinecraftTerrainMesh geometry, float[] lights, Throwable failure) {
            this(task, geometry, lights, failure, 0L, null);
        }

        SectionResult withWorkerCompletion(long nanos, Object stamp) {
            return new SectionResult(task, geometry, lights, failure, nanos, stamp);
        }
    }

    private record PendingGeometryGroup(long groupKey, ResourcePackEpoch materialEpoch,
                                        List<MinecraftTerrainGeometry.Change> operations,
                                        PendingPublication publication, long enqueuedNanos) { }

    private record PendingPublication(List<PublishedPut> puts, List<PublishedDrop> drops) { }

    private record PublishedPut(long key, int originX, int originY, int originZ, float[] lights, long token,
                                Object extractionStamp, Object readyStamp, long workerCompletedNanos) { }

    private record PublishedDrop(long key, long token) { }

    private record PublishedSection(int originX, int originY, int originZ, float[] lights) { }

    private boolean shouldRebase(int rbx, int rby, int rbz) {
        return Math.abs(rbx - blockX) >= rebaseDistanceBlocks()
                || Math.abs(rby - blockY) >= rebaseDistanceBlocks()
                || Math.abs(rbz - blockZ) >= rebaseDistanceBlocks();
    }

    private void applyBuildChanges(List<SectionResult> prepared, LongOpenHashSet removed,
                                   boolean rebase, int rbx, int rby, int rbz) {
        for (SectionResult result : prepared) submitSection(result);
        for (LongIterator it = removed.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            submitDrop(key);
        }
        if (rebase) {
            blockX = rbx;
            blockY = rby;
            blockZ = rbz;
        }
    }

    private void submitDirtyGroup(DirtyGroup group) {
        ArrayList<MinecraftTerrainGeometry.Change> operations = new ArrayList<>();
        ArrayList<SectionResult> puts = new ArrayList<>();
        LongArrayList drops = new LongArrayList();
        for (SectionResult result : group.prepared) appendPut(operations, puts, result);
        for (LongIterator it = group.removed.iterator(); it.hasNext(); ) appendDrop(operations, drops, it.nextLong());
        if (!operations.isEmpty()) enqueueGroup(group.id, operations, puts, drops);
    }

    private void submitSection(SectionResult result) {
        ArrayList<MinecraftTerrainGeometry.Change> operations = new ArrayList<>(1);
        ArrayList<SectionResult> puts = new ArrayList<>(1);
        appendPut(operations, puts, result);
        enqueueGroup(result.task().key, operations, puts, new LongArrayList());
    }

    private void submitDrop(long key) {
        if (pendingDrops.contains(key)) return;
        ArrayList<MinecraftTerrainGeometry.Change> operations = new ArrayList<>(1);
        LongArrayList drops = new LongArrayList(1);
        appendDrop(operations, drops, key);
        enqueueGroup(key, operations, new ArrayList<>(), drops);
    }

    private void appendPut(List<MinecraftTerrainGeometry.Change> operations, List<SectionResult> puts, SectionResult result) {
        SectionTask task = result.task();
        long key = task.key;
        operations.add(new MinecraftTerrainGeometry.Put(key, task.sox, task.soy, task.soz, result.geometry()));
        puts.add(result);
    }

    private void appendDrop(List<MinecraftTerrainGeometry.Change> operations, LongArrayList drops, long key) {
        if (pendingDrops.contains(key)) return;
        operations.add(new MinecraftTerrainGeometry.Drop(key));
        drops.add(key);
    }

    private void enqueueGroup(long groupKey, List<MinecraftTerrainGeometry.Change> operations,
                              List<SectionResult> puts, LongArrayList drops) {
        for (SectionResult put : puts) {
            long key = put.task().key;
            pendingPublications.add(key);
            pendingDrops.remove(key);
        }
        for (LongIterator it = drops.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            pendingPublications.add(key);
            pendingDrops.add(key);
        }
        ArrayList<PublishedPut> publicationPuts = new ArrayList<>(puts.size());
        for (SectionResult put : puts) {
            SectionTask task = put.task();
            long token = ++nextPublicationToken;
            pendingPublicationToken.put(task.key, token);
            publicationPuts.add(new PublishedPut(task.key, task.sox, task.soy, task.soz, put.lights(), token,
                    task.extractionStamp, put.readyStamp(), put.workerCompletedNanos()));
        }
        ArrayList<PublishedDrop> publicationDrops = new ArrayList<>(drops.size());
        for (LongIterator it = drops.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            long token = ++nextPublicationToken;
            pendingPublicationToken.put(key, token);
            publicationDrops.add(new PublishedDrop(key, token));
        }
        long enqueuedNanos = System.nanoTime();
        for (PublishedPut put : publicationPuts) {
            recordTerrainLatency("terrainWorkerToPending", put.workerCompletedNanos(), enqueuedNanos);
        }
        ResourcePackEpoch publicationEpoch = puts.isEmpty()
                ? java.util.Objects.requireNonNull(materialLookup, "materialLookup").epoch()
                : puts.getFirst().task().materialEpoch;
        pendingGeometryGroups.add(new PendingGeometryGroup(groupKey, publicationEpoch,
                List.copyOf(operations),
                new PendingPublication(List.copyOf(publicationPuts), List.copyOf(publicationDrops)), enqueuedNanos));
        instrumentation.max("terrainPendingGeometryGroups", pendingGeometryGroups.size());
    }

    private void submitPendingGeometry() {
        MinecraftMaterialLookup lookup = materialLookup;
        if (pendingGeometryGroups.isEmpty() || lookup == null) return;
        discardStalePendingGeometry(lookup.epoch());
        if (pendingGeometryGroups.isEmpty() || retainedGeometry == null) return;
        LinkedHashMap<Long, PendingGeometryGroup> latest = new LinkedHashMap<>();
        for (PendingGeometryGroup group : pendingGeometryGroups) {
            latest.put(group.groupKey(), group);
        }
        long submittedNanos = System.nanoTime();
        for (PendingGeometryGroup group : latest.values()) {
            int putCount = group.publication().puts().size();
            for (int i = 0; i < putCount; i++) {
                recordTerrainLatency("terrainPendingToSubmit", group.enqueuedNanos(), submittedNanos);
            }
        }
        retainedGeometry.submitGroup(latest.values().stream()
                .map(PendingGeometryGroup::operations)
                .toList());
        for (PendingGeometryGroup group : latest.values()) {
            acknowledgePublication(group.publication(), submittedNanos);
            pendingGeometryGroups.removeIf(pending -> pending.groupKey() == group.groupKey());
        }
    }

    private void discardStalePendingGeometry(ResourcePackEpoch activeEpoch) {
        pendingGeometryGroups.removeIf(group -> {
            if (group.materialEpoch().equals(activeEpoch)) return false;
            PendingPublication publication = group.publication();
            for (PublishedPut put : publication.puts()) {
                if (clearPendingPublication(put.key(), put.token())) enqueueMissingIfNeeded(put.key());
            }
            for (PublishedDrop drop : publication.drops()) {
                if (clearPendingPublication(drop.key(), drop.token())) enqueueMissingIfNeeded(drop.key());
            }
            instrumentation.count("terrainMaterialEpochRejects",
                    publication.puts().size() + publication.drops().size());
            return true;
        });
    }

    static List<Long> latestGroupKeys(List<Long> keys) {
        LinkedHashMap<Long, Long> latest = new LinkedHashMap<>();
        for (Long key : keys) latest.put(key, key);
        return List.copyOf(latest.keySet());
    }

    private void acknowledgePublication(PendingPublication publication, long submittedNanos) {
        boolean lightsChanged = false;
        for (PublishedPut put : publication.puts()) {
            long key = put.key();
            float[] lights = put.lights();
            PublishedSection previous = publishedSections.put(key, new PublishedSection(
                    put.originX(), put.originY(), put.originZ(), lights));
            clearPendingPublication(key, put.token());
            instrumentation.published(put.extractionStamp());
            instrumentation.published(put.readyStamp());
            recordTerrainLatency("terrainSubmitToPublication", submittedNanos, System.nanoTime());
            empty.remove(key);
            lightsChanged |= !sameLightRecords(previous == null ? null : previous.lights(), lights);
            updateLightSection(key, publishedSections.get(key));
        }
        for (PublishedDrop drop : publication.drops()) {
            long key = drop.key();
            PublishedSection previous = publishedSections.remove(key);
            clearPendingPublication(key, drop.token());
            lightsChanged |= previous != null && hasLights(previous.lights());
            removeLightSection(key);
            if (emptyAfterDrop(desired.contains(key))) empty.add(key);
            else empty.remove(key);
        }
        if (lightsChanged) markLightSnapshotDirty();
    }

    private void recordTerrainLatency(String metric, long startNanos, long endNanos) {
        long micros = Math.max(0L, endNanos - startNanos) / 1_000L;
        instrumentation.count(metric + "Samples", 1);
        instrumentation.count(metric + "MicrosTotal", micros);
        instrumentation.max(metric + "MicrosMax", micros);
    }

    private boolean clearPendingPublication(long key, long token) {
        if (pendingPublicationToken.get(key) != token) return false;
        pendingPublicationToken.remove(key);
        pendingPublications.remove(key);
        pendingDrops.remove(key);
        return true;
    }

    private static boolean hasLights(float[] lights) {
        return lights != null && lights.length > 0;
    }

    /** Null and an empty collector result both mean that the section contributes no lights. */
    static boolean sameLightRecords(float[] previous, float[] current) {
        if (!hasLights(previous) && !hasLights(current)) return true;
        return Arrays.equals(previous, current);
    }

    private void updateLightSection(long key, PublishedSection section) {
        if (!hasLights(section.lights())) {
            removeLightSection(key);
            return;
        }
        lightSections.put(key, MinecraftTerrainLightAdapter.describe(key, ++lightGroupRevision,
                section.originX(), section.originY(), section.originZ(), section.lights()));
    }

    private void removeLightSection(long key) {
        lightSections.remove(key);
    }

    private void markLightSnapshotDirty() {
        lightSnapshotDirty = true;
    }

    /** Publish a new immutable input generation after the edit-coalescing interval. */
    private void flushLightSnapshotUpdate() {
        if (!lightSnapshotDirty) return;
        long now = System.nanoTime();
        if (lastLightSnapshotNanos != 0L
                && now - lastLightSnapshotNanos < LIGHT_SNAPSHOT_UPDATE_INTERVAL_NANOS) {
            return;
        }
        // Snapshot the sorted values once; unchanged generations are reused by subsequent frame updates.
        retainedLights = new MinecraftTerrainLightSnapshot(List.copyOf(lightSections.values()),
                ++retainedLightGeneration);
        lightSnapshotDirty = false;
        lastLightSnapshotNanos = now;
    }

    /** Join outstanding CPU meshing tasks and discard their unsubmitted results. */
    private void drainTasksForClear() {
        awaitActiveTasks();
        Throwable failure = null;
        SectionResult result;
        while ((result = completedBuilds.poll()) != null) {
            if (result.failure() != null && failure == null) {
                failure = result.failure();
            }
        }
        inFlight.clear();
        inFlightDirtyGroup.clear();
        if (failure != null) {
            throw new RuntimeException("RT terrain worker/build failed during teardown", failure);
        }
    }

    /** Full teardown joins CPU work; the scene manager owns all submitted GPU resources. */
    private void clear(boolean shutdown) {
        terrainEpoch++;
        if (shutdown) {
            workers.shutdown();
            drainTasksForClear();
        }
        cancelAllDirtyGroups();
        publishedSections.clear();
        pendingPublications.clear();
        pendingDrops.clear();
        pendingPublicationToken.clear();
        inFlight.clear();
        inFlightDirtyGroup.clear();
        snapshots.clear();
        synchronized (dirtyLock) {
            dirty.clear(); // any pending re-extract keys refer to the old world/coords — drop them
            dirtyEvents.clear();
            dirtyPending = false;
        }
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        missingIndex.clear();
        queuedDirtyGroup.clear();
        reextract.clear();
        queuedReextract.clear();
        windowValid = false;
        lightSections.clear();
        lightGroupRevision = 0L;
        lightSnapshotDirty = false;
        lastLightSnapshotNanos = 0L;
        retainedLights = MinecraftTerrainLightSnapshot.empty(++retainedLightGeneration);
        empty.clear();
        removed.clear();
        prepared.clear();
        pendingGeometryGroups.clear();
        nextPublicationToken = 0L;
    }

    private static long columnKey(int scx, int scz) {
        return ((long) scx << 32) ^ (scz & 0xFFFFFFFFL);
    }

    private static int columnX(long key) {
        return (int) (key >> 32);
    }

    private static int columnZ(long key) {
        return (int) key;
    }

    /** Pack section coords into a stable map key; ranges fit comfortably in the masks. */
    static long sectionKey(int scx, int scy, int scz) {
        return (scx & 0x3FFFFFFL) | ((scz & 0x3FFFFFFL) << 26) | ((scy & 0xFFFL) << 52);
    }

    private static int sectionX(long key) {
        return (int) (key << 38 >> 38);
    }

    private static int sectionZ(long key) {
        return (int) (key << 12 >> 38);
    }

    private static int sectionY(long key) {
        return (int) (key >> 52);
    }

}
