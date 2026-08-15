

package dev.comfyfluffy.caustica.minecraft.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.geometry.RtPackedGeometry;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.rt.light.RtRetainedLightScene;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
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
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.WORKER_TESS;
import static dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.buildCpuSection;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.CpuSection;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.PackedSection;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrainMesher.WorkerTessState;
/**
 * Per-section terrain residency synced to vanilla's loaded chunks. A singleton manager
 * keeps a map of resident 16³ sections. The 20 TPS tick maintains the desired window around the player
 * (slid incrementally on section-boundary crossings) and drains dirty events; the actual streaming —
 * snapshot dispatch, completion drain, and publish — runs once per render frame from
 * {@link RtComposite} with count-bounded completion and dispatch passes rather than a per-tick burst.
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
 * immutable packed CPU geometry and opacity-micromap input. The scene manager owns all GPU preparation,
 * publication, and retirement.
 */
public final class RtTerrain {
    private static final ResourceId GEOMETRY_SOURCE = ResourceId.of("caustica", "terrain");
    private static final float[] IDENTITY_TRANSFORM = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
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
    // Light edits and streaming completions can arrive every frame. Collapse them behind the currently
    // building generation and cap full hierarchy snapshots/uploads without adding noticeable edit latency.
    private static final long LIGHT_HIERARCHY_UPDATE_INTERVAL_NANOS = 50_000_000L;

    private static int rebaseDistanceBlocks() {
        return CausticaConfig.Rt.Terrain.REBASE_DISTANCE_BLOCKS.value();
    }

    private static final RtTerrain INSTANCE = new RtTerrain();

    private RtSceneGeometryManager geometry;
    // Persistent palette snapshots for tessellation regions (render-thread only); invalidated on dirty
    // sections, column unload/window-leave, and full clears.
    private final RtSectionSnapshots snapshots = new RtSectionSnapshots();
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
    // Published state changes only from the manager's publication acknowledgment. Pending markers keep
    // CPU residency coherent while a queued group waits for manager preparation and atomic application.
    private final Long2ObjectOpenHashMap<PublishedSection> publishedSections = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pendingPublications = new LongOpenHashSet();
    private final LongOpenHashSet pendingDrops = new LongOpenHashSet();
    private final Long2LongOpenHashMap pendingPublicationRevision = new Long2LongOpenHashMap();
    private final LongOpenHashSet removed = new LongOpenHashSet();
    private final ArrayList<SectionResult> prepared = new ArrayList<>();
    private long publicationRevision;
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
    // Frame origin (player block after a distance threshold) for ray offsets and the light hierarchy.
    public int blockX;
    public int blockY;
    public int blockZ;
    /** Coalesced asynchronous, atomically published retained finite-light hierarchy. */
    private final RtRetainedLightScene retainedLightScene =
            new RtRetainedLightScene(RtWorkerPool.INSTANCE::submit);
    /** Sorted light-only snapshot, updated with section publication instead of rescanning all geometry. */
    private final TreeMap<Integer, RetainedLightBatch> lightSections = new TreeMap<>();
    private final Long2IntOpenHashMap lightSlots = new Long2IntOpenHashMap();
    private final LongArrayList freeLightSlots = new LongArrayList();
    private int nextLightSlot;
    private boolean lightHierarchyDirty;
    private long lastLightHierarchyRequestNanos;
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

    private RtTerrain() {
        missingIndex.defaultReturnValue(NO_MISSING_INDEX);
        queuedDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
        inFlight.defaultReturnValue(NO_TESS_TOKEN);
        inFlightDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
        pendingPublicationRevision.defaultReturnValue(Long.MIN_VALUE);
        lightSlots.defaultReturnValue(-1);
    }

    /** Attach this producer to the renderer-owned retained scene before it submits any terrain work. */
    public static void attachGeometry(RtSceneGeometryManager sceneGeometry) {
        if (INSTANCE.geometry == null) {
            INSTANCE.geometry = sceneGeometry;
        }
    }

    /**
     * The manager if it has valid (possibly zero-instance) retained geometry state to trace against, else null.
     * Null only while genuinely uninitialized (no world, or mid-teardown) — a transient empty-residency
     * window (world join, dimension change, a full evict) still returns non-null so the RT frame keeps
     * tracing (sky/entities only) instead of a caller falling back to vanilla.
     */
    public static RtTerrain currentOrNull() {
        return INSTANCE.geometry != null ? INSTANCE : null;
    }

    public static boolean isSectionReady(BlockPos blockPos) {
        int scx = SectionPos.blockToSectionCoord(blockPos.getX());
        int scy = SectionPos.blockToSectionCoord(blockPos.getY());
        int scz = SectionPos.blockToSectionCoord(blockPos.getZ());
        long key = sectionKey(scx, scy, scz);
        return INSTANCE.geometry != null && (INSTANCE.isPublished(key) || INSTANCE.empty.contains(key));
    }

    public RtRetainedLightScene.PublishedState retainedLights() {
        return retainedLightScene.published();
    }

    /** Per-tick residency update: window sync + dirty drain (plus the streaming fallback, see {@link #frame}). */
    public static void update(GpuContext ctx) {
        INSTANCE.tick(ctx);
    }

    /**
     * Per-render-frame streaming pass, driven by {@link RtComposite#composite}: publish completed builds
     * and dispatch immutable snapshots to workers, bounded by configured per-pass counts.
     */
    public static void frame(GpuContext ctx) {
        if (RtMaterialRegistry.INSTANCE.isReady()) INSTANCE.frameStream(ctx);
    }

    public static void shutdown(GpuContext ctx) {
        INSTANCE.clear(ctx, true);
        INSTANCE.geometry = null;
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
    public static void markBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        RtTerrain terrain = INSTANCE;
        synchronized (terrain.dirtyLock) {
            LongArrayList keys = new LongArrayList();
            for (int scx = (minX - 1) >> 4; scx <= (maxX + 1) >> 4; scx++) {
                for (int scy = (minY - 1) >> 4; scy <= (maxY + 1) >> 4; scy++) {
                    for (int scz = (minZ - 1) >> 4; scz <= (maxZ + 1) >> 4; scz++) {
                        keys.add(sectionKey(scx, scy, scz));
                    }
                }
            }
            if (!keys.isEmpty()) {
                long groupId = ++terrain.dirtyGroupSeq;
                if (groupId == NO_DIRTY_GROUP) {
                    groupId = ++terrain.dirtyGroupSeq;
                }
                terrain.dirtyEvents.add(new DirtyEvent(groupId, keys));
                terrain.dirtyPending = true;
            }
        }
    }

    /**
     * Request a full residency clear, applied on the next {@link #tick} (render thread, where the RT
     * context is available). Wired to vanilla's
     * {@link net.minecraft.client.renderer.extract.LevelExtractor#allChanged()}, which fires on a
     * dimension change (via {@code setLevel}), a render-distance change, and F3+A. Thread-safe.
     */
    public static void requestFullClear() {
        RtTerrainOmm.clearCache();
        INSTANCE.fullClearRequested = true;
    }

    private void tick(GpuContext ctx) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            if (!noWorldClearApplied) {
                clear(ctx, false);
                noWorldClearApplied = true;
            }
            return;
        }
        noWorldClearApplied = false;
        if (!RtMaterialRegistry.INSTANCE.isReady()) {
            return; // resource reload gap: keep old work dormant until the new epoch requests a full clear
        }

        // Full clear on an explicit invalidation — vanilla's LevelExtractor.allChanged(). That fires on a
        // dimension switch (setLevel → allChanged),
        // render-distance change, and F3+A. Without it, End→Overworld keeps the old dimension's geometry:
        // residency is keyed by raw section coords (no world identity), so the same coords stay resident
        // and are never rebuilt for the new world.
        if (fullClearRequested) {
            fullClearRequested = false;
            clear(ctx, false);
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
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.windowSync")) {
            syncDesiredWindow(chunkSource, pcx, psy, pcz, r, loY, hiY, removed);
        }

        // Re-extract edited sections. Drain under a short lock so concurrent block updates are not lost.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.dirtyDrain")) {
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
        }
        // Dispatch/drain/build normally runs per render frame (RtComposite → frame()). If no frame has
        // streamed recently — loading screen, no world rendering — drive it from here with the bigger
        // bounded fallback pass so the world still fills.
        if (System.nanoTime() - lastFrameStreamNanos > STREAM_FALLBACK_AFTER_NANOS) {
            stream(ctx);
        }
    }

    /** The per-render-frame entry point: run one count-bounded streaming pass. */
    private void frameStream(GpuContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        lastFrameStreamNanos = System.nanoTime();
        stream(ctx);
    }

    /**
     * One streaming pass: drain completed CPU builds, submit ready sections, and dispatch
     * new section snapshots to the worker pool. Per-pass result and dispatch caps bound render-thread
     * work. Skips silently when there is nothing to do (no stats row).
     */
    private void stream(GpuContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (reextract.isEmpty() && missing.isEmpty()
                && completedBuilds.isEmpty()
                && !retainedLightScene.hasCompletions()
                && !lightHierarchyDirty
                && removed.isEmpty() && prepared.isEmpty()) {
            return;
        }
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;

        ClientChunkCache chunkSource = level.getChunkSource();

        // Drain completed CPU builds first — publication is visible fill progress, so it gets priority.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.drainCompletion")) {
            drainCompletedBuilds(ctx, prepared, removed, completionResultsPerPass());
        }

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.publish")) {
                applyBuildChanges(ctx, prepared, removed, shouldRebase(pbx, pby, pbz), pbx, pby, pbz);
                removed.clear();
                prepared.clear();
            }
        }

        // Publish only a fully uploaded hierarchy. Newer section changes supersede stale worker/upload
        // results, while the previous complete generation remains active until this atomic swap.
        if (retainedLightScene.hasCompletions()) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.lightScenePublish")) {
                retainedLightScene.publishReady(ctx);
            }
        }

        // Snapshot and dispatch a bounded number of new worker-owned section builds.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.snapshotDispatch")) {
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
        }

        flushLightHierarchyUpdate(ctx);

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
        RtFrameStats.FRAME.count("sectionsSnapshotted", 1);
        RtSectionSnapshots.Region region = snapshots.createRegion(dispatch.level(), sx, sy, sz);
        long token = ++buildToken;
        long dirtyGroup = queuedDirtyGroup.remove(key);
        if (dirtyGroup != NO_DIRTY_GROUP && !dirtyGroups.containsKey(dirtyGroup)) {
            dirtyGroup = NO_DIRTY_GROUP;
        }
        RtMaterialRegistry.Snapshot materialSnapshot = RtMaterialRegistry.INSTANCE.requireSnapshot();
        SectionTask task = new SectionTask(key, token, sx << 4, sy << 4, sz << 4, dirtyGroup,
                terrainEpoch, materialSnapshot.epoch());
        beginActiveTask();
        try {
            RtWorkerPool.INSTANCE.submit(() -> {
                try {
                    if (!isTaskCurrent(task)) {
                        completeEmptyTask(task);
                        return;
                    }
                    WorkerTessState ws = WORKER_TESS.get(); // thread-confined; reset per task, arrays amortized
                    ws.reset(dispatch.blockColors());
                    CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), ws.blockRandom, ws.modelParts,
                            ws.capture, dispatch.fluidModelSet(), ws.fluidCapture, ws.mesh, ws.pos,
                            materialSnapshot, sx, sy, sz);
                    if (!isTaskCurrent(task)) {
                        completeEmptyTask(task);
                        return;
                    }
                    PackedSection packed = cpu.packed();
                    if (packed == null) {
                        completeEmptyTask(task);
                    } else {
                        RtPackedGeometry<float[]> packedGeometry = new RtPackedGeometry<>(packed.positions(),
                                packed.indices(), packed.uvs(), packed.material(), packed.classTris(),
                                packed.triBase(), packed.lights());
                        completeTask(new SectionResult(task, packedGeometry, cpu.opacityMicromap(), null));
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
            if (isTaskCurrent(result.task())) completedBuilds.add(result);
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
     * manager submission queue.
     */
    private void drainCompletedBuilds(GpuContext ctx, List<SectionResult> prepared, LongOpenHashSet removed,
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
                    && task.materialEpoch == RtMaterialRegistry.INSTANCE.epoch();
            if (!valid) {
                if (tokenValid) {
                    inFlight.remove(task.key);
                    inFlightDirtyGroup.remove(task.key);
                    long staleGroup = task.dirtyGroup;
                    if (staleGroup != NO_DIRTY_GROUP) cancelDirtyGroup(staleGroup);
                    enqueueMissingIfNeeded(task.key);
                    RtFrameStats.FRAME.count("terrainMaterialEpochRejects", 1);
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
                completeDirtyGroupMember(ctx, group);
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

    private void completeDirtyGroupMember(GpuContext ctx, DirtyGroup group) {
        if (--group.remaining > 0) {
            return;
        }
        dirtyGroups.remove(group.id);
        submitDirtyGroup(ctx, group);
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
        final long materialEpoch;
        SectionTask(long key, long token, int sox, int soy, int soz, long dirtyGroup,
                    long terrainEpoch, long materialEpoch) {
            this.key = key;
            this.token = token;
            this.sox = sox;
            this.soy = soy;
            this.soz = soz;
            this.dirtyGroup = dirtyGroup;
            this.terrainEpoch = terrainEpoch;
            this.materialEpoch = materialEpoch;
        }
    }

    private record SectionResult(SectionTask task, RtPackedGeometry<float[]> geometry,
                                 RtAccel.OpacityMicromapInput opacityInput, Throwable failure) { }

    private record PublishedSection(int originX, int originY, int originZ, float[] lights) { }

    private boolean shouldRebase(int rbx, int rby, int rbz) {
        return Math.abs(rbx - blockX) >= rebaseDistanceBlocks()
                || Math.abs(rby - blockY) >= rebaseDistanceBlocks()
                || Math.abs(rbz - blockZ) >= rebaseDistanceBlocks();
    }

    private void applyBuildChanges(GpuContext ctx, List<SectionResult> prepared, LongOpenHashSet removed,
                                   boolean rebase, int rbx, int rby, int rbz) {
        for (SectionResult result : prepared) submitSection(ctx, result);
        for (LongIterator it = removed.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            submitDrop(ctx, key);
        }
        if (rebase) {
            blockX = rbx;
            blockY = rby;
            blockZ = rbz;
            markLightHierarchyDirty();
        }
    }

    private void submitDirtyGroup(GpuContext ctx, DirtyGroup group) {
        ArrayList<RtSceneGeometryManager.GeometryOperation> operations = new ArrayList<>();
        for (SectionResult result : group.prepared) appendPut(operations, result);
        for (LongIterator it = group.removed.iterator(); it.hasNext(); ) appendDrop(operations, it.nextLong());
        if (!operations.isEmpty()) submitGroup(operations);
    }

    private void submitSection(GpuContext ctx, SectionResult result) {
        ArrayList<RtSceneGeometryManager.GeometryOperation> operations = new ArrayList<>(2);
        appendPut(operations, result);
        submitGroup(operations);
    }

    private void submitDrop(GpuContext ctx, long key) {
        if (pendingDrops.contains(key)) return;
        ArrayList<RtSceneGeometryManager.GeometryOperation> operations = new ArrayList<>(2);
        appendDrop(operations, key);
        submitGroup(operations);
    }

    private void appendPut(List<RtSceneGeometryManager.GeometryOperation> operations, SectionResult result) {
        SectionTask task = result.task();
        long key = task.key;
        operations.add(new RtSceneGeometryManager.Put(key, new RtSceneGeometryManager.RetainedPayload(result.geometry(),
                result.opacityInput(), CausticaConfig.Rt.Terrain.BLAS_COMPACTION.value(),
                RtGeometryAbi.FLAG_RECEIVES_PROJECTED_SURFACE_MODIFIERS)));
        operations.add(new RtSceneGeometryManager.Place(key, key, IDENTITY_TRANSFORM, 0xff,
                new SceneOrigin(task.sox, task.soy, task.soz)));
    }

    private static void appendDrop(List<RtSceneGeometryManager.GeometryOperation> operations, long key) {
        operations.add(new RtSceneGeometryManager.Remove(key));
        operations.add(new RtSceneGeometryManager.Drop(key));
    }

    private void submitGroup(List<RtSceneGeometryManager.GeometryOperation> operations) {
        long revision = ++publicationRevision;
        for (RtSceneGeometryManager.GeometryOperation operation : operations) {
            switch (operation) {
                case RtSceneGeometryManager.Put put -> {
                    pendingPublicationRevision.put(put.residentKey(), revision);
                    pendingPublications.add(put.residentKey());
                    pendingDrops.remove(put.residentKey());
                }
                case RtSceneGeometryManager.Drop drop -> {
                    pendingPublicationRevision.put(drop.residentKey(), revision);
                    pendingPublications.add(drop.residentKey());
                    pendingDrops.add(drop.residentKey());
                }
                default -> { }
            }
        }
        geometry.submit(List.of(new RtSceneGeometryManager.GeometryUpdateGroup(
                new RtSceneGeometryManager.GroupKey(GEOMETRY_SOURCE, revision), revision, operations)),
                this::acknowledgePublication);
    }

    private void acknowledgePublication(RtSceneGeometryManager.PublicationAck acknowledgement) {
        if (geometry == null || !acknowledgement.key().source().equals(GEOMETRY_SOURCE)) return;
        boolean lightsChanged = false;
        for (RtSceneGeometryManager.GeometryOperation operation : acknowledgement.operations()) {
            switch (operation) {
                case RtSceneGeometryManager.Put put -> {
                    if (!(put.payload() instanceof RtSceneGeometryManager.RetainedPayload retained)) continue;
                    float[] lights = (float[]) retained.geometry().metadata();
                    long key = put.residentKey();
                    PublishedSection previous = publishedSections.put(key, new PublishedSection(
                            sectionX(key) << 4, sectionY(key) << 4, sectionZ(key) << 4, lights));
                    clearPendingPublication(key, acknowledgement.revision());
                    empty.remove(key);
                    lightsChanged |= !sameLightRecords(previous == null ? null : previous.lights(), lights);
                    updateLightSection(key, publishedSections.get(key));
                }
                case RtSceneGeometryManager.Drop drop -> {
                    long key = drop.residentKey();
                    PublishedSection previous = publishedSections.remove(key);
                    clearPendingPublication(key, acknowledgement.revision());
                    lightsChanged |= previous != null && hasLights(previous.lights());
                    removeLightSection(key);
                    if (emptyAfterDrop(desired.contains(key))) empty.add(key);
                    else empty.remove(key);
                }
                default -> { }
            }
        }
        if (lightsChanged) markLightHierarchyDirty();
    }

    private void clearPendingPublication(long key, long revision) {
        if (pendingPublicationRevision.get(key) <= revision) {
            pendingPublicationRevision.remove(key);
            pendingPublications.remove(key);
            pendingDrops.remove(key);
        }
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
        int slot = lightSlots.get(key);
        if (slot < 0) {
            slot = freeLightSlots.isEmpty() ? nextLightSlot++ : (int) freeLightSlots.popLong();
            lightSlots.put(key, slot);
        }
        lightSections.put(slot, MinecraftTerrainLightAdapter.describe(slot,
                section.originX(), section.originY(), section.originZ(), section.lights()));
    }

    private void removeLightSection(long key) {
        int slot = lightSlots.remove(key);
        if (slot >= 0) {
            lightSections.remove(slot);
            freeLightSlots.add(slot);
        }
    }

    private void markLightHierarchyDirty() {
        lightHierarchyDirty = true;
    }

    /** Snapshot only lit sections once the previous complete generation has published. */
    private void flushLightHierarchyUpdate(GpuContext ctx) {
        if (!lightHierarchyDirty || !retainedLightScene.isIdle()) return;
        long now = System.nanoTime();
        if (lastLightHierarchyRequestNanos != 0L
                && now - lastLightHierarchyRequestNanos < LIGHT_HIERARCHY_UPDATE_INTERVAL_NANOS) {
            return;
        }
        // The manager creates one immutable worker snapshot directly from the sorted values view.
        var player = Minecraft.getInstance().player;
        RtRetainedLightScene.DebugFocus debugFocus = player != null
                ? new RtRetainedLightScene.DebugFocus(player.getX(), player.getY(), player.getZ())
                : null;
        retainedLightScene.request(ctx, lightSections.values(), blockX, blockY, blockZ,
                MinecraftTerrainLightAdapter.METERS_PER_WORLD_UNIT, debugFocus);
        lightHierarchyDirty = false;
        lastLightHierarchyRequestNanos = now;
    }

    /** Join outstanding CPU meshing tasks and discard their unsubmitted results. */
    private void drainTasksForClear() {
        awaitActiveTasks();
        retainedLightScene.awaitIdle();
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
    private void clear(GpuContext ctx, boolean shutdown) {
        terrainEpoch++;
        retainedLightScene.cancelPending();
        if (shutdown) {
            RtWorkerPool.INSTANCE.shutdown();
            drainTasksForClear();
        } else {
            retainedLightScene.invalidate(ctx, ctx.gpuExecutor().latestGraphicsUse());
        }
        if (geometry != null) {
            geometry.clearSource(ctx, GEOMETRY_SOURCE);
        }
        cancelAllDirtyGroups();
        publishedSections.clear();
        pendingPublications.clear();
        pendingDrops.clear();
        pendingPublicationRevision.clear();
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
        retainedLightScene.destroyAfterDeviceIdle();
        lightSections.clear();
        lightSlots.clear();
        freeLightSlots.clear();
        nextLightSlot = 0;
        lightHierarchyDirty = false;
        lastLightHierarchyRequestNanos = 0L;
        empty.clear();
        removed.clear();
        prepared.clear();
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
