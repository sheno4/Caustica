package dev.comfyfluffy.caustica.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.ParticleEngineAccessor;
import dev.comfyfluffy.caustica.mixin.ParticleGroupAccessor;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Dynamic entities as real ray-traced {@code ModelPart} geometry. Each frame, every model entity is
 * re-posed and captured ({@link RtEntityCollector} + {@link RtEntityCapture}) into canonical packed
 * arrays. The engine-owned scene geometry manager owns their resident GPU geometry, acceleration
 * structures, table records, instances, and graphics lifetime. This source retains only Minecraft
 * capture state, block-entity change detection, and stale-entry eviction state.
 * Non-model entities (items/arrows — geometry via submitItem/submitBlockModel, which the collector
 * ignores) are skipped.
 *
 * <p>Per-frame capture is capped by {@code -Dcaustica.rt.maxEntities}. Build class and topology version
 * are declared with each capture so the manager can choose reusable, refit, or rebuilt geometry safely.
 */
public final class RtEntities {
    public static final RtEntities INSTANCE = new RtEntities();
    private static final ResourceId ENTITY_GEOMETRY = ResourceId.of("caustica", "entity_geometry");
    private static final ResourceId BLOCK_ENTITY_GEOMETRY = ResourceId.of("caustica", "block_entity_geometry");
    private static final ResourceId PARTICLE_GEOMETRY = ResourceId.of("caustica", "particle_geometry");
    private static final long PARTICLE_KEY = 0L;
    public static boolean enabled() {
        return CausticaConfig.Rt.Entities.ENABLED.value();
    }

    // TLAS visibility-mask bits, ANDed against the per-ray cull mask in world.rgen. Bit 0 = secondary rays
    // (shadows / GI / reflections, CULL_SECONDARY); bit 1 = the primary camera ray (CULL_PRIMARY).
    private static final int MASK_SECONDARY = 0x01;
    private static final int MASK_PRIMARY = 0x02;
    /** Default mask: visible to every ray (terrain and ordinary entities use this). */
    private static final int MASK_ALL = 0xFF;
    /** Particles are primary-ray-only: visible/lit by the camera path, invisible to shadows/GI/reflections. */
    private static final int PARTICLE_MASK = MASK_PRIMARY;
    public static boolean particlesEnabled() {
        return CausticaConfig.Rt.Entities.PARTICLES_ENABLED.value();
    }
    public static boolean glowEnabled() {
        return CausticaConfig.Rt.Entities.GLOW_ENABLED.value();
    }
    public static boolean nameTagsEnabled() {
        return CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED.value();
    }

    private static int maxEntities() {
        return CausticaConfig.Rt.Entities.maxEntities();
    }

    private static int maxOrdinaryEntities() {
        return CausticaConfig.Rt.Entities.MAX_ORDINARY_ENTITIES.value();
    }

    private static int maxBlockEntities() {
        return CausticaConfig.Rt.Entities.MAX_BLOCK_ENTITIES.value();
    }

    private static int maxParticles() {
        return CausticaConfig.Rt.Entities.MAX_PARTICLES.value();
    }

    private static int entityListCapacity() {
        return CausticaConfig.Rt.Entities.entityListCapacity();
    }

    private static int entityMapCapacity() {
        return CausticaConfig.Rt.Entities.entityMapCapacity();
    }

    // Chunk radius around the player to scan for block entities (chests/signs/…) each frame.
    private static int beViewChunks() {
        return CausticaConfig.Rt.Entities.BE_VIEW_CHUNKS.value();
    }

    // Block entities keep a keyed cached mesh. Each frame the BE is re-meshed (cheap) and its mesh hashed;
    // the manager replaces GPU geometry only when it changed, so static
    // BEs cost no GPU work while animating ones (chest lid, spawner, …) rebuild every frame. New/changed
    // rebuilds are capped per frame so a burst of newly loaded chunks can't stall (over-budget BEs keep
    // their last geometry / pop in over later frames, like terrain's worker dispatch budget).
    private static int beBuildsPerFrame() {
        return CausticaConfig.Rt.Entities.BE_BUILDS_PER_FRAME.value();
    }

    // Stale-cache eviction horizon.
    private static final int KEEP_FRAMES = 4;

    // Identity 3x4 row-major. Particles are captured in rebased space while ordinary entity geometry is
    // captured around its anchor and placed by a translation instance transform.
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    // Reusable capture pipeline (single-threaded on the render thread).
    private final RtEntityCollector collector = new RtEntityCollector();
    private final RtEntityCapture capture = new RtEntityCapture();
    private final PoseStack entityPoseStack = new PoseStack();
    private final PoseStack blockEntityPoseStack = new PoseStack();
    private CameraRenderState cameraState;
    // Particle capture funnels MC billboard quads into the shared entity mesh. Rebuilt particle topology
    // has no stable vertex correspondence, so the scene manager resets its object-motion history.
    private final RtParticleCapture particleCapture = new RtParticleCapture(capture);
    private final QuadParticleRenderState particleScratch = new QuadParticleRenderState();
    private final float[] particleCenterScratch = new float[3];


    // This frame's glowing entities (see GlowEntity) + the camera-relative offset (camera pos - rebase
    // origin) their positions are captured against, for GlowOutlineFeature's raster pass. Rebuilt every frame.
    private final List<GlowEntity> glowBatches = new ArrayList<>();
    private float glowCamOffsetX, glowCamOffsetY, glowCamOffsetZ;

    /** This frame's glowing entities, or an empty list if none (or glow is disabled). */
    public List<GlowEntity> glowBatches() {
        return glowBatches;
    }

    public float glowCamOffsetX() {
        return glowCamOffsetX;
    }

    public float glowCamOffsetY() {
        return glowCamOffsetY;
    }

    public float glowCamOffsetZ() {
        return glowCamOffsetZ;
    }

    // This frame's visible name tags (see NameTagEntity), captured off the SAME EntityRenderState vanilla's
    // own EntityRenderer.extractNameTags already populates (shouldShowName/crosshair-look/distance rules,
    // computed as a side effect of the dispatcher.extractEntity call captureEntities already makes) — no
    // reimplementation of that logic. Positions are rebase-space (same convention as glowBatches); consumed
    // by NameTagFeature's raster pass, which reuses glowCamOffset{X,Y,Z} (same camera, same frame).
    private final List<NameTagEntity> nameTagBatches = new ArrayList<>();

    /** This frame's visible name tags, or an empty list if none (or name tags are disabled). */
    public List<NameTagEntity> nameTagBatches() {
        return nameTagBatches;
    }

    /** This frame's camera orientation (view-to-world rotation) — the billboard rotation name tags face. */
    public Quaternionf cameraOrientation() {
        return cameraState.orientation;
    }

    // CPU capture state for engine-owned dynamic residents.
    private final Int2ObjectOpenHashMap<EntityState> entityStates = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // Persistent per-block-entity geometry, keyed by BlockPos.asLong().
    private final Map<Long, BeEntry> beCache = new HashMap<>();
    private final List<BeCandidate> beCandidates = new ArrayList<>();
    private final ArrayDeque<BeCandidate> beCandidatePool = new ArrayDeque<>();
    // (Re)builds recorded so far this frame, reset each beginFrame; gates new BE builds to BE_BUILDS_PER_FRAME.
    private int beBuildsThisFrame;
    private final Set<RtSceneGeometryManager.GroupKey> pendingDrops = new LinkedHashSet<>();
    private long nextGeometryRevision;

    private RtEntities() {
    }

    /**
     * Cached block-entity geometry. The mesh is captured in <b>block-local</b> space (identity submit pose),
     * so it is rebase-independent — only the per-frame TLAS instance transform ({@code blockPos − rebase})
     * changes, exactly like a terrain section.
     */
    private static final class BeEntry {
        int bx, by, bz;                          // block position (drives the per-frame instance transform)
        long meshHash;                           // hash of the captured mesh — rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window — for eviction
        final PublicationState publication = new PublicationState();
    }

    /** CPU-only capture state for one entity; the manager owns its resident. */
    private static final class EntityState {
        long lastSeen;
    }

    /** Tracks whether a block-entity resident has reached the manager's published snapshot. */
    static final class PublicationState {
        boolean published;
        boolean putPending;
        long lastSubmittedRevision;

        void submitted(long revision, boolean includesPut) {
            lastSubmittedRevision = revision;
            putPending |= includesPut;
        }

        void acknowledged(long revision) {
            published = true;
            if (lastSubmittedRevision == revision) putPending = false;
        }
    }

    /** One glowing entity's body mesh (rebased-space positions, copied out of {@link #capture} before the
     *  next entity resets it) plus its vanilla outline colour, for {@code GlowOutlineFeature}'s full-res raster
     *  mask pass. Captured as a side effect of the normal RT capture — no extra posing/animation work. */
    public record GlowEntity(float[] verts, int[] idx, int color) {
    }

    /** One visible name tag: display text + the attachment point's world position (rebase-space, same
     *  convention as entity capture — see {@link RtEntities#glowCamOffsetX()} for the camera-relative
     *  offset needed to finish the transform to camera-relative space). */
    public record NameTagEntity(Component text, float x, float y, float z) {
    }

    private static final class BeCandidate {
        BlockEntity be;
        double dist2;
        long posKey;

        void set(BlockEntity be, double dist2, long posKey) {
            this.be = be;
            this.dist2 = dist2;
            this.posKey = posKey;
        }
    }

    private static final Comparator<BeCandidate> BE_CANDIDATE_ORDER = (a, b) -> {
        int byDistance = Double.compare(a.dist2, b.dist2);
        return byDistance != 0 ? byDistance : Long.compare(a.posKey, b.posKey);
    };

    /** Mutable per-frame build state shared by the entity + block-entity capture passes. */
    private final class FrameBuild {
        final RtSceneGeometryManager geometry;
        final SceneOrigin origin;
        final RtMaterialRegistry.Snapshot materials;
        final List<RtSceneGeometryManager.GeometryUpdateGroup> groups = new ArrayList<>();
        final Map<RtSceneGeometryManager.GroupKey, Acknowledgment> acknowledgments = new HashMap<>();
        int count;        // geometry-table entries / TLAS instances
        int logicalCount; // ordinary entities + block entities + individual particles

        FrameBuild(RtSceneGeometryManager geometry, SceneOrigin origin, RtMaterialRegistry.Snapshot materials) {
            this.geometry = geometry;
            this.origin = origin;
            this.materials = materials;
        }

        long submit(ResourceId source, long key, List<RtSceneGeometryManager.GeometryOperation> operations,
                    LongConsumer acknowledgment) {
            RtSceneGeometryManager.GroupKey groupKey = new RtSceneGeometryManager.GroupKey(source, key);
            pendingDrops.remove(groupKey);
            long revision = ++nextGeometryRevision;
            groups.add(new RtSceneGeometryManager.GeometryUpdateGroup(groupKey, revision, operations));
            if (acknowledgment != null) acknowledgments.put(groupKey, new Acknowledgment(revision, acknowledgment));
            return revision;
        }

        void flush() {
            if (groups.isEmpty()) return;
            geometry.submit(groups, published -> {
                Acknowledgment acknowledgment = acknowledgments.get(published.key());
                if (acknowledgment != null && acknowledgment.revision == published.revision()) {
                    acknowledgment.consumer.accept(published.revision());
                }
            });
        }

        boolean full() {
            return logicalCount >= maxEntities();
        }
    }

    private record Acknowledgment(long revision, LongConsumer consumer) { }

    /**
     * Capture this frame's model entities, block entities, and particles into independent source groups.
     * Dynamic entity coordinates are local and placed by instances; particles remain captured relative to
     * the authored scene origin with an identity instance.
     */
    public void beginFrame(GpuContext ctx, RtSceneGeometryManager geometry, SceneOrigin origin,
                           double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        RtMaterialRegistry registry = RtMaterialRegistry.INSTANCE;
        FrameBuild build = new FrameBuild(geometry, origin, registry.requireSnapshot());
        int rbx = (int) origin.x();
        int rby = (int) origin.y();
        int rbz = (int) origin.z();
        if (!enabled()) {
            clearResidents(build);
            finishFrame(build);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            finishFrame(build);
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        setCamera(camX, camY, camZ, projection, viewRotation);

        var defaultMaterialResolver = capture.baseColorMaterialResolver;
        capture.baseColorMaterialResolver = (bindingId, textureIndex) ->
                registry.withBaseColorTextureIndex(build.materials, bindingId, textureIndex);
        try {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.capture")) {
                captureEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blockEntities")) {
                captureBlockEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.particles")) {
                captureParticles(ctx, build, mc, partial, rbx, rby, rbz, projection, viewRotation);
            }
        } catch (RuntimeException | Error t) {
            // Quiesce old frames and drop source capture state before propagating the original failure.
            ctx.waitIdle();
            shutdown(ctx);
            throw t;
        } finally {
            capture.baseColorMaterialResolver = defaultMaterialResolver;
        }
        evictStaleAccels(build);
        evictStaleBes(build);
        finishFrame(build);
    }

    private void finishFrame(FrameBuild build) {
        List<RtSceneGeometryManager.GroupKey> drops = List.copyOf(pendingDrops);
        pendingDrops.clear();
        for (RtSceneGeometryManager.GroupKey key : drops) {
            build.submit(key.source(), key.key(), List.of(
                    new RtSceneGeometryManager.Remove(key.key()),
                    new RtSceneGeometryManager.Drop(key.key())), null);
        }
        build.flush();
    }

    /** Capture visible model entities (mobs, items, falling blocks). */
    private void captureEntities(GpuContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Entity cameraEntity = mc.getCameraEntity();
        // In first person the camera owner's own body must not block the primary camera ray, but it should
        // still appear in reflections / shadows / GI (so the player sees themselves in water as others would).
        // In F5 third person it renders fully, like any other entity.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        glowBatches.clear();
        nameTagBatches.clear();
        boolean glow = glowEnabled();
        boolean nameTags = nameTagsEnabled();
        glowCamOffsetX = (float) (cameraState.pos.x - rbx);
        glowCamOffsetY = (float) (cameraState.pos.y - rby);
        glowCamOffsetZ = (float) (cameraState.pos.z - rbz);
        resetPoseStack(entityPoseStack);
        int capturedThisFrame = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (build.full() || capturedThisFrame >= maxOrdinaryEntities()) {
                break;
            }
            if (entity.isInvisible()) {
                continue;
            }
            boolean firstPersonSelf = entity == cameraEntity && firstPerson;
            int mask = firstPersonSelf ? MASK_SECONDARY : MASK_ALL;
            float ix;
            float iy;
            float iz;
            int id = entity.getId();
            capture.reset();
            try {
                EntityRenderState state;
                long extractStart = RtFrameStats.FRAME.startStage();
                try {
                    state = dispatcher.extractEntity(entity, partial);
                } finally {
                    RtFrameStats.FRAME.endStage("entity.capture.extract", extractStart);
                }
                // Derive placement from the extracted state so the submitted pose and TLAS anchor use the
                // same interpolation result.
                ix = (float) state.x;
                iy = (float) state.y;
                iz = (float) state.z;
                // extractEntity already ran EntityRenderer.extractNameTags (shouldShowName, crosshair-look,
                // distance cutoff, the attachment point) as a normal part of building the render state — no
                // need to reimplement any of that here, just read the result. Name tags billboard to face
                // the camera every frame, so their camera-facing geometry belongs to the separate
                // WorldOverlayPass raster path rather than this mesh capture.
                if (nameTags && !firstPersonSelf && state.nameTag != null) {
                    captureNameTag(level, state, ix, iy, iz, rbx, rby, rbz);
                }
                collector.begin(capture, true);
                resetPoseStack(entityPoseStack);
                // Capture around the entity anchor. Per-frame placement moves into the TLAS instance,
                // so ordinary world translation no longer changes the mesh or its float precision.
                long submitStart = RtFrameStats.FRAME.startStage();
                try {
                    dispatcher.submit(state, cameraState, 0.0, 0.0, 0.0, entityPoseStack, collector);
                } finally {
                    RtFrameStats.FRAME.endStage("entity.capture.submit", submitStart);
                }
            } catch (Throwable t) {
                // Fail loud instead of skip-and-limp: a capture throw here is almost always our bug, and
                // swallowing it leaves the entity invisible every frame plus a per-frame MC CrashReport.
                // Propagate to composite(), which logs the full trace, disables RT, and reverts to vanilla.
                throw new RuntimeException("RT entity capture failed", t);
            } finally {
                collector.begin(null, false);
                resetPoseStack(entityPoseStack);
            }
            if (capture.isEmpty()) {
                continue; // non-model entity (arrow/etc.) — no body geometry captured
            }
            if (glow && !firstPersonSelf) {
                // Vanilla never draws the local player's own body in first person (no model to outline —
                // only the held-item hand), so it never shows the Glowing outline on yourself either. Our
                // capture still meshes the first-person self (for reflections/shadows/GI), so the glow mask
                // must explicitly skip it to match — otherwise it'd show an outline vanilla never would.
                int glowColor = collector.outlineColor();
                if (glowColor != 0) {
                    glowBatches.add(new GlowEntity(copyTranslatedVertices(capture.verts,
                            ix - rbx, iy - rby, iz - rbz), capture.idx.toIntArray(), glowColor));
                }
            }
            appendCapture(build, id, mask, translationTransform(ix - rbx, iy - rby, iz - rbz));
            build.logicalCount++;
            RtFrameStats.FRAME.count("entitiesCaptured", 1);
            capturedThisFrame++;
        }
    }

    private static void resetPoseStack(PoseStack poseStack) {
        while (!poseStack.isEmpty()) {
            poseStack.popPose();
        }
        poseStack.setIdentity();
    }

    private static float[] copyTranslatedVertices(FloatArrayList local, float tx, float ty, float tz) {
        float[] placed = new float[local.size()];
        float[] src = local.elements();
        for (int i = 0; i < local.size(); i += 3) {
            placed[i] = src[i] + tx;
            placed[i + 1] = src[i + 1] + ty;
            placed[i + 2] = src[i + 2] + tz;
        }
        return placed;
    }

    /**
     * Gather one entity's name tag (world position + text) into {@link #nameTagBatches}, unless a block is
     * in the way. {@code state.nameTagAttachment} is only non-null when {@code state.nameTag} is (both set
     * together in {@code EntityRenderer.extractNameTags}). Positions are world-space (unrebased) until the
     * very end, matching {@code level.clip}'s coordinate space; the rebase subtraction happens last.
     *
     * <p>Vanilla draws a translucent "ghost" copy of the tag through walls (see {@code
     * SubmitNodeCollection.submitNameTag}'s {@code seeThroughNameTags} phase) instead of hiding it — v1
     * here just hides occluded tags, a simplification to avoid a second draw/blend mode; revisit if that
     * turns out to look wrong in practice.
     */
    private void captureNameTag(ClientLevel level, EntityRenderState state, float ix, float iy, float iz,
                                 int rbx, int rby, int rbz) {
        Vec3 attach = state.nameTagAttachment;
        if (attach == null) {
            return;
        }
        double wx = ix + attach.x;
        double wy = iy + attach.y + 0.5;
        double wz = iz + attach.z;
        // The 5-arg (Vec3,Vec3,Block,Fluid,Entity) overload NPEs on a null entity (it unconditionally builds
        // an EntityCollisionContext via CollisionContext.of, which requireNonNulls it) — this raycast isn't
        // for any particular entity's own collision shape, so pass an empty CollisionContext directly.
        HitResult hit = level.clip(new ClipContext(cameraState.pos, new Vec3(wx, wy, wz),
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.MISS) {
            return; // a block is between the camera and the tag
        }
        nameTagBatches.add(new NameTagEntity(state.nameTag,
                (float) wx - rbx, (float) wy - rby, (float) wz - rbz));
    }

    /**
     * Capture this frame's billboard particles as one combined rebuilt mesh (cutout, camera-only receiver).
     * Each particle is extracted into {@link #particleScratch}, funneled through {@link #particleCapture},
     * and merged into one primary-only instance.
     */
    private void captureParticles(GpuContext ctx, FrameBuild build, Minecraft mc, float partial,
                                  int rbx, int rby, int rbz, Matrix4f projection, Matrix4f viewRotation) {
        capture.reset();
        int particleLimit = maxParticles();
        if (!particlesEnabled() || particleLimit == 0 || build.full()) {
            submitParticles(build);
            return;
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) {
            submitParticles(build);
            return;
        }
        Map<ParticleRenderType, ParticleGroup<?>> groups =
                ((ParticleEngineAccessor) mc.particleEngine).caustica$getParticleGroups();
        if (groups == null || groups.isEmpty()) {
            submitParticles(build);
            return;
        }
        RtMaterialRegistry registry = RtMaterialRegistry.INSTANCE;
        // Billboards are thin two-sided scatterers, which their material says with a transmission weight;
        // every layer shares the Minecraft source's named material and pairs it with its own atlas slot.
        // The transparent border is absent geometry, so the producer derives cutout coverage from the
        // named binding captured in this resource epoch.
        int particleMaterial = build.materials.bindingId(MinecraftMaterialSource.PARTICLE_BILLBOARD);
        capture.currentMaterialId = registry.withCutoutCoverage(build.materials, particleMaterial);
        capture.currentSbtClass = registry.sbtClassFor(capture.currentMaterialId);
        // extract() emits camera-relative positions; shift them into rebased space (identity instance).
        Vec3 camPos = cam.position();
        particleCapture.setOffset((float) (camPos.x - rbx), (float) (camPos.y - rby), (float) (camPos.z - rbz));
        // Reject particles whose world-space bounds are wholly outside before paying extract/build-layer
        // cost. The center test after extraction retains the existing exact inclusion behavior for bounds
        // which intersect the frustum.
        Frustum frustum = new Frustum(viewRotation, projection);
        frustum.prepare(camPos.x, camPos.y, camPos.z);
        int particlesCaptured = 0;
        try {
            particleGroups:
            for (ParticleGroup<?> group : groups.values()) {
                Queue<? extends Particle> queue = ((ParticleGroupAccessor) group).caustica$getParticles();
                for (Particle p : queue) {
                    if (build.full() || particlesCaptured >= particleLimit) {
                        break particleGroups;
                    }
                    if (!(p instanceof SingleQuadParticle sq)) {
                        continue; // item-pickup / elder-guardian particles aren't billboard quads (skip)
                    }
                    if (!frustum.isVisible(p.getBoundingBox())) {
                        continue;
                    }
                    int vb = capture.verts.size(), ib = capture.idx.size();
                    int ub = capture.uvList.size(), prb = capture.prim.size(), abb = capture.sbtClasses.size();
                    int vertBefore = vb / 3;
                    particleScratch.clear();
                    sq.extract(particleScratch, cam, partial);
                    for (SingleQuadParticle.Layer layer : particleScratch.layers()) {
                        capture.currentBaseColorTextureIndex = RtEntityTextures.INSTANCE.slotForAtlas(
                                layer.textureAtlasLocation());
                        particleScratch.buildLayer(layer, particleCapture);
                        particleCapture.flush();
                    }
                    int vertAfter = capture.verts.size() / 3;
                    if (vertAfter == vertBefore) {
                        continue; // nothing captured for this particle
                    }
                    particleCenter(vertBefore, vertAfter, particleCenterScratch);
                    // pointInFrustum wants the world position: rebased center + rebase origin.
                    if (!frustum.pointInFrustum(particleCenterScratch[0] + rbx, particleCenterScratch[1] + rby, particleCenterScratch[2] + rbz)) {
                        capture.verts.size(vb); // off-screen → truncate this particle back out (clean quad boundary)
                        capture.idx.size(ib);
                        capture.uvList.size(ub);
                        capture.prim.size(prb);
                        capture.sbtClasses.size(abb);
                        continue;
                    }
                    build.logicalCount++;
                    particlesCaptured++;
                }
            }
        } catch (Throwable t) {
            capture.reset();
            throw new RuntimeException("RT particle capture failed", t); // propagate to composite() (see entity path)
        }
        RtFrameStats.FRAME.count("particlesCaptured", particlesCaptured);
        if (capture.isEmpty()) {
            submitParticles(build);
            return;
        }
        submitParticles(build);
    }

    /** Replaces the one merged particle resident, or removes it when the capture is empty. */
    private void submitParticles(FrameBuild build) {
        List<RtSceneGeometryManager.GeometryOperation> operations;
        if (capture.isEmpty()) {
            operations = List.of(new RtSceneGeometryManager.Remove(PARTICLE_KEY),
                    new RtSceneGeometryManager.Drop(PARTICLE_KEY));
        } else {
            RtEntityCapture.PackedGeometry packed = capture.packGeometry();
            RtSceneGeometryManager.PackedInput input = packedInput(packed, RtSceneGeometryManager.BuildClass.REBUILT);
            operations = List.of(
                    new RtSceneGeometryManager.Put(PARTICLE_KEY, new RtSceneGeometryManager.IndexedPayload(input)),
                    new RtSceneGeometryManager.Place(PARTICLE_KEY, PARTICLE_KEY, IDENTITY, PARTICLE_MASK, build.origin));
        }
        build.submit(PARTICLE_GEOMETRY, PARTICLE_KEY, operations, null);
    }

    /** Average (rebase-space) position of a captured particle's verts — approximates the particle center. */
    private void particleCenter(int vertBefore, int vertAfter, float[] out) {
        float[] v = capture.verts.elements();
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = vertBefore; i < vertAfter; i++) {
            cx += v[i * 3];
            cy += v[i * 3 + 1];
            cz += v[i * 3 + 2];
        }
        int vc = vertAfter - vertBefore;
        out[0] = cx / vc;
        out[1] = cy / vc;
        out[2] = cz / vc;
    }

    /**
     * Capture block entities (chests, signs, …). Each BE keeps a cached mesh keyed by BlockPos.
     * Every frame the BE is re-meshed (cheap) and its mesh hashed; the manager replaces its resident only when
     * the mesh actually changed — so static BEs cost no GPU work while animating ones (chest lid, spawner,
     * …) rebuild every frame and stay smooth. New/changed rebuilds are capped at {@link
     * #BE_BUILDS_PER_FRAME} per frame so a burst of newly loaded chunks can't stall; over-budget BEs keep
     * their last geometry / pop in over later frames. Captured block-local → placed by a translate-only
     * instance transform; static, so the MV is 0.
     */
    private void captureBlockEntities(GpuContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        beBuildsThisFrame = 0;
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        beDispatcher.prepare(cameraState.pos); // sets the camera for shouldRender / extract
        long now = RtComposite.frameCounter();
        int pcx = rbx >> 4, pcz = rbz >> 4;
        Vec3 cam = cameraState.pos;
        List<BeCandidate> candidates = beCandidates;
        for (int i = 0; i < candidates.size(); i++) {
            BeCandidate candidate = candidates.get(i);
            candidate.be = null;
            beCandidatePool.addLast(candidate);
        }
        candidates.clear();
        int viewChunks = beViewChunks();
        for (int cx = pcx - viewChunks; cx <= pcx + viewChunks; cx++) {
            for (int cz = pcz - viewChunks; cz <= pcz + viewChunks; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz) || !(level.getChunk(cx, cz) instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    double dx = p.getX() + 0.5 - cam.x;
                    double dy = p.getY() + 0.5 - cam.y;
                    double dz = p.getZ() + 0.5 - cam.z;
                    BeCandidate candidate = beCandidatePool.pollFirst();
                    if (candidate == null) {
                        candidate = new BeCandidate();
                    }
                    candidate.set(be, dx * dx + dy * dy + dz * dz, p.asLong());
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.size() > 1) {
            candidates.sort(BE_CANDIDATE_ORDER);
        }
        int firstBlockEntity = build.count;
        for (BeCandidate candidate : candidates) {
            if (build.full() || build.count - firstBlockEntity >= maxBlockEntities()) {
                break;
            }
            updateBlockEntity(ctx, build, beDispatcher, candidate.be, partial, now, rbx, rby, rbz);
        }
    }

    /** Re-mesh one block entity; replace its cached resident only if the mesh changed (budgeted); then emit it. */
    private void updateBlockEntity(GpuContext ctx, FrameBuild build, BlockEntityRenderDispatcher beDispatcher,
                                   BlockEntity be, float partial, long now, int rbx, int rby, int rbz) {
        capture.reset();
        try {
            BlockEntityRenderState state = beDispatcher.tryExtractRenderState(be, partial, null, false);
            if (state == null) {
                return; // off-screen-only (beacon/end-gateway), distance-culled, or no renderer
            }
            collector.begin(capture, false);
            // Identity pose ⇒ block-local mesh; world placement is the per-frame instance transform in emitBe.
            resetPoseStack(blockEntityPoseStack);
            beDispatcher.submit(state, blockEntityPoseStack, collector, cameraState);
        } catch (Throwable t) {
            throw new RuntimeException("RT block-entity capture failed", t); // propagate to composite() (see entity path)
        } finally {
            resetPoseStack(blockEntityPoseStack);
            collector.begin(null, false);
        }
        if (capture.isEmpty()) {
            return;
        }
        long key = be.getBlockPos().asLong();
        BeEntry entry = beCache.get(key);
        if (entry != null) {
            entry.lastSeen = now;
        }
        long hash = meshHash();
        float[] transform = {1, 0, 0, be.getBlockPos().getX() - rbx, 0, 1, 0, be.getBlockPos().getY() - rby,
                0, 0, 1, be.getBlockPos().getZ() - rbz};
        if (entry == null || entry.meshHash != hash) {
            // Geometry changed (or new BE) → rebuild, but only within this frame's budget. Over budget: keep
            // showing the previous geometry; a brand-new BE simply pops in over the next frames.
            if (beBuildsThisFrame >= beBuildsPerFrame()) {
                if (entry != null) {
                    emitBe(build, entry, transform);
                }
                return;
            }
            BeEntry rebuilt = buildBe(build, entry, be, hash, transform);
            rebuilt.lastSeen = now;
            beCache.put(key, rebuilt);
            build.count++;
            build.logicalCount++;
            RtFrameStats.FRAME.count("blockEntitiesCaptured", 1);
            return;
        }
        emitBe(build, entry, transform);
    }

    /** Submits a keyed dynamic resident so compatible block-entity mesh updates retain vertex history. */
    private BeEntry buildBe(FrameBuild build, BeEntry entry, BlockEntity be, long hash, float[] transform) {
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        BlockPos p = be.getBlockPos();
        RtSceneGeometryManager.PackedInput input = packedInput(packed, RtSceneGeometryManager.BuildClass.DEFORMING);
        beBuildsThisFrame++;

        BeEntry e = entry != null ? entry : new BeEntry();
        e.bx = p.getX();
        e.by = p.getY();
        e.bz = p.getZ();
        e.meshHash = hash;
        long revision = build.submit(BLOCK_ENTITY_GEOMETRY, p.asLong(), List.of(
                new RtSceneGeometryManager.Put(p.asLong(), new RtSceneGeometryManager.IndexedPayload(input)),
                new RtSceneGeometryManager.Place(p.asLong(), p.asLong(), transform, MASK_ALL, build.origin)),
                e.publication::acknowledged);
        e.publication.submitted(revision, true);
        return e;
    }

    /** FNV-1a hash of the currently captured mesh (positions + indices + per-prim data) for rebuild detection. */
    private long meshHash() {
        long h = 1469598103934665603L;
        float[] v = capture.verts.elements();
        int vn = capture.verts.size();
        for (int i = 0; i < vn; i++) {
            h = (h ^ (Float.floatToRawIntBits(v[i]) & 0xffffffffL)) * 1099511628211L;
        }
        int[] x = capture.idx.elements();
        int xn = capture.idx.size();
        for (int i = 0; i < xn; i++) {
            h = (h ^ (x[i] & 0xffffffffL)) * 1099511628211L;
        }
        float[] pr = capture.prim.elements();
        int pn = capture.prim.size();
        for (int i = 0; i < pn; i++) {
            h = (h ^ (Float.floatToRawIntBits(pr[i]) & 0xffffffffL)) * 1099511628211L;
        }
        int[] classes = capture.sbtClasses.elements();
        for (int i = 0; i < capture.sbtClasses.size(); i++) {
            h = (h ^ (classes[i] & 0xffffffffL)) * 1099511628211L;
        }
        return h;
    }

    /** Emits an unchanged block entity through its manager-owned dynamic resident. */
    private void emitBe(FrameBuild build, BeEntry e, float[] transform) {
        if (build.full()) {
            return;
        }
        long key = BlockPos.asLong(e.bx, e.by, e.bz);
        List<RtSceneGeometryManager.GeometryOperation> operations;
        boolean includesPut = !e.publication.published;
        if (!includesPut) {
            operations = List.of(new RtSceneGeometryManager.Place(key, key, transform, MASK_ALL, build.origin));
        } else {
            RtEntityCapture.PackedGeometry packed = capture.packGeometry();
            RtSceneGeometryManager.PackedInput input = packedInput(packed, RtSceneGeometryManager.BuildClass.DEFORMING);
            operations = List.of(new RtSceneGeometryManager.Put(key, new RtSceneGeometryManager.IndexedPayload(input)),
                    new RtSceneGeometryManager.Place(key, key, transform, MASK_ALL, build.origin));
        }
        long revision = build.submit(BLOCK_ENTITY_GEOMETRY, key, operations, e.publication::acknowledged);
        e.publication.submitted(revision, includesPut);
        build.count++;
        build.logicalCount++;
        RtFrameStats.FRAME.count("blockEntitiesCaptured", 1);
    }

    /** Drop cached block entities not seen (in window) within the last KEEP_FRAMES frames — unloaded/out of view. */
    private void evictStaleBes(FrameBuild build) {
        if (beCache.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        Iterator<Map.Entry<Long, BeEntry>> it = beCache.entrySet().iterator();
        while (it.hasNext()) {
            BeEntry e = it.next().getValue();
            if (now - e.lastSeen < KEEP_FRAMES) {
                continue;
            }
            long key = BlockPos.asLong(e.bx, e.by, e.bz);
            build.submit(BLOCK_ENTITY_GEOMETRY, key, List.of(
                    new RtSceneGeometryManager.Remove(key), new RtSceneGeometryManager.Drop(key)), null);
            it.remove();
        }
    }

    private static float[] translationTransform(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    private void appendCapture(FrameBuild build, int entityId, int mask,
                               float[] instanceTransform) {
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        RtSceneGeometryManager.PackedInput input = packedInput(packed, RtSceneGeometryManager.BuildClass.DEFORMING);
        EntityState state = entityStates.computeIfAbsent(entityId, unused -> new EntityState());
        state.lastSeen = RtComposite.frameCounter();
        build.submit(ENTITY_GEOMETRY, entityId, List.of(
                new RtSceneGeometryManager.Put(entityId, new RtSceneGeometryManager.IndexedPayload(input)),
                new RtSceneGeometryManager.Place(entityId, entityId, instanceTransform, mask, build.origin)), null);
        build.count++;
    }

    private RtSceneGeometryManager.PackedInput packedInput(RtEntityCapture.PackedGeometry packed,
                                                            RtSceneGeometryManager.BuildClass buildClass) {
        return new RtSceneGeometryManager.PackedInput(
                java.util.Arrays.copyOf(capture.verts.elements(), capture.verts.size()),
                java.util.Arrays.copyOf(packed.indices().elements(), packed.indices().size()),
                java.util.Arrays.copyOf(capture.uvList.elements(), capture.uvList.size()),
                java.util.Arrays.copyOf(packed.primitives().elements(), packed.primitives().size()),
                packed.copyClassTris(), 0, topologyVersion(packed), buildClass,
                RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES);
    }

    private long topologyVersion(RtEntityCapture.PackedGeometry packed) {
        long hash = 1469598103934665603L;
        hash = (hash ^ (capture.verts.size() / 3)) * 1099511628211L;
        for (int i = 0; i < packed.indices().size(); i++) hash = (hash ^ packed.indices().getInt(i)) * 1099511628211L;
        for (int value : packed.classTris()) hash = (hash ^ value) * 1099511628211L;
        return hash;
    }

    private void evictStaleAccels(FrameBuild build) {
        long now = RtComposite.frameCounter();
        var it = entityStates.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().lastSeen < KEEP_FRAMES) continue;
            int id = entry.getIntKey();
            build.submit(ENTITY_GEOMETRY, id, List.of(
                    new RtSceneGeometryManager.Remove(id), new RtSceneGeometryManager.Drop(id)), null);
            it.remove();
        }
    }

    /** Remove every source-owned resident when this capture path is disabled. */
    private void clearResidents(FrameBuild build) {
        for (int id : entityStates.keySet()) {
            build.submit(ENTITY_GEOMETRY, id, List.of(
                    new RtSceneGeometryManager.Remove(id), new RtSceneGeometryManager.Drop(id)), null);
        }
        for (long key : beCache.keySet()) {
            build.submit(BLOCK_ENTITY_GEOMETRY, key, List.of(
                    new RtSceneGeometryManager.Remove(key), new RtSceneGeometryManager.Drop(key)), null);
        }
        build.submit(PARTICLE_GEOMETRY, PARTICLE_KEY, List.of(
                new RtSceneGeometryManager.Remove(PARTICLE_KEY), new RtSceneGeometryManager.Drop(PARTICLE_KEY)), null);
        entityStates.clear();
        beCache.clear();
    }

    private void setCamera(double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        if (cameraState == null) {
            cameraState = new CameraRenderState();
        }
        cameraState.pos = new Vec3(camX, camY, camZ);
        cameraState.projectionMatrix.set(projection);
        cameraState.viewRotationMatrix.set(viewRotation);
        // viewRotation is the world->view rotation (mvCurProjView = frameProjection * frameViewRotation);
        // vanilla's Camera.rotation() (what CameraRenderState.orientation actually holds, per Camera.java
        // "cameraState.orientation.set(this.rotation())") is the INVERSE of that — view->world, i.e. the
        // camera's own facing direction, used to billboard world-space quads (name tags) to face the
        // camera. A pure rotation's inverse is its conjugate. Nothing consumed this field before
        // NameTagFeature; a plain setFromUnnormalized(viewRotation) here would billboard backwards.
        cameraState.orientation.setFromUnnormalized(viewRotation).conjugate();
        cameraState.initialized = true;
    }

    /** Drop CPU templates that retain resource-pack-owned model trees. */
    public void onResourceReload() {
        for (int id : entityStates.keySet()) pendingDrops.add(new RtSceneGeometryManager.GroupKey(ENTITY_GEOMETRY, id));
        for (long key : beCache.keySet()) pendingDrops.add(new RtSceneGeometryManager.GroupKey(BLOCK_ENTITY_GEOMETRY, key));
        pendingDrops.add(new RtSceneGeometryManager.GroupKey(PARTICLE_GEOMETRY, PARTICLE_KEY));
        entityStates.clear();
        beCache.clear();
        collector.clearCaches();
    }

    /** Clears this source's published snapshot before entity IDs can be reused by a new world. */
    public void onWorldChanged(GpuContext ctx, RtSceneGeometryManager geometry) {
        geometry.clearSource(ctx, ENTITY_GEOMETRY);
        geometry.clearSource(ctx, BLOCK_ENTITY_GEOMETRY);
        geometry.clearSource(ctx, PARTICLE_GEOMETRY);
        entityStates.clear();
        beCache.clear();
        pendingDrops.clear();
        collector.clearCaches();
    }

    /** Clear capture state; the geometry manager owns and tears down all GPU residents. */
    public void shutdown(GpuContext ctx) {
        entityStates.clear();
        beCache.clear();
        glowBatches.clear();
        nameTagBatches.clear();
        resetPoseStack(blockEntityPoseStack);
        beCandidates.clear();
        beCandidatePool.clear();
        pendingDrops.clear();
        collector.clearCaches();
    }
}
