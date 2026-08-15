package dev.comfyfluffy.caustica.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.ParticleEngineAccessor;
import dev.comfyfluffy.caustica.mixin.ParticleGroupAccessor;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
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
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Dynamic entities as real ray-traced {@code ModelPart} geometry. Each frame, every model entity is
 * re-posed and captured ({@link RtEntityCollector} + {@link RtEntityCapture}) into canonical packed
 * arrays. The engine-owned scene geometry manager owns their resident GPU geometry, acceleration
 * structures, table records, instances, and graphics lifetime. This source retains only Minecraft
 * capture state, semantic change detection, motion inputs, and rigid-fitting references.
 * Non-model entities (items/arrows — geometry via submitItem/submitBlockModel, which the collector
 * ignores) are skipped.
 *
 * <p>Per-frame capture is capped by {@code -Dcaustica.rt.maxEntities}. Build class and topology version
 * are declared with each capture so the manager can choose reusable, refit, or rebuilt geometry safely.
 */
public final class RtEntities {
    public static final RtEntities INSTANCE = new RtEntities();
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
    // Rigid reuse: when this frame's capture is a rigid transform (translation and/or yaw) of the mesh the
    // entity's AS was last built from, reference that AS with the fitted TLAS instance transform and skip
    // the mesh upload + refit entirely — still mobs, item frames/armor stands, and spinning/bobbing
    // dropped items become table-entry + instance writes only.

    // Max per-axis residual (blocks) for a capture to count as a rigid transform of the reference mesh.
    // Well below a texel (1/16 block) and DLSS-RR jitter; float pose math noise is ~1e-5.
    private static final float RIGID_FIT_EPS = 2.0e-3f;


    // Treat per-vertex displacements as rigid when every vertex agrees within this tolerance, avoiding a
    // transient disp buffer for plain whole-entity translation.
    private static final float RIGID_DISP_EPS = 1.0e-5f;
    // Identity 3x4 row-major. Particles are already captured in rebased space; dynamic entities use a
    // translate/yaw instance transform because their geometry is captured around the entity anchor.
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final Motion NO_MOTION = new Motion(null, 0, 0f, 0f, 0f);

    // Reusable capture pipeline (single-threaded on the render thread).
    private final RtEntityCollector collector = new RtEntityCollector();
    private final RtEntityCapture capture = new RtEntityCapture();
    private final PoseStack entityPoseStack = new PoseStack();
    private final PoseStack blockEntityPoseStack = new PoseStack();
    private CameraRenderState cameraState;
    // Particle capture: a VertexConsumer adapter that funnels MC's billboard quads into `capture` (the
    // shared entity mesh). We extract each live particle into `particleScratch`, accumulate per-vertex
    // motion-vector displacements in `particleDisp`, and key the previous-frame center off particle
    // identity in `particlePrev` (rebuilt each frame → prunes dead particles).
    private final RtParticleCapture particleCapture = new RtParticleCapture(capture);
    private final QuadParticleRenderState particleScratch = new QuadParticleRenderState();
    private final FloatArrayList particleDisp = new FloatArrayList();
    private IdentityHashMap<Particle, ParticlePrev> particlePrev = new IdentityHashMap<>();
    private IdentityHashMap<Particle, ParticlePrev> particleCur = new IdentityHashMap<>();
    private final float[] particleCenterScratch = new float[3];

    /** Previous frame's particle center (rebase-space) + that frame's rebase origin, for the MV diff. */
    private static final class ParticlePrev {
        float cx, cy, cz;
        int rbx, rby, rbz;

        void set(float cx, float cy, float cz, int rbx, int rby, int rbz) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.rbx = rbx;
            this.rby = rby;
            this.rbz = rbz;
        }
    }

    // Previous frame's captured entity-local vertex positions + its interpolated world anchor, keyed by
    // entity id. Maps are swapped/reused each frame: entries not seen this frame fall out, while visible
    // entities keep their float[] backing to avoid steady-state allocation churn.
    private Int2ObjectOpenHashMap<EntityPrev> prevVerts = new Int2ObjectOpenHashMap<>(entityMapCapacity());
    private Int2ObjectOpenHashMap<EntityPrev> curVerts = new Int2ObjectOpenHashMap<>(entityMapCapacity());

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

    /** Last frame's posed mesh for one entity: local vertex positions + its interpolated world anchor. */
    private static final class EntityPrev {
        float[] verts = new float[0];
        int size;
        float anchorX, anchorY, anchorZ;
    }

    // CPU capture state for engine-owned deforming residents.
    private final Int2ObjectOpenHashMap<EntityState> entityStates = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // Persistent per-block-entity geometry, keyed by BlockPos.asLong(). Built once and reused every frame.
    private final Map<Long, BeEntry> beCache = new HashMap<>();
    private final List<BeCandidate> beCandidates = new ArrayList<>();
    private final ArrayDeque<BeCandidate> beCandidatePool = new ArrayDeque<>();
    // (Re)builds recorded so far this frame, reset each beginFrame; gates new BE builds to BE_BUILDS_PER_FRAME.
    private int beBuildsThisFrame;
    private RtSceneGeometryManager.DynamicFrame activeGeometry;

    private RtEntities() {
    }

    /**
     * Cached block-entity geometry. The mesh is captured in <b>block-local</b> space (identity submit pose),
     * so it is rebase-independent — only the per-frame TLAS instance transform ({@code blockPos − rebase})
     * changes, exactly like a terrain section. The manager retains the keyed resident until it is evicted
     * (out of window / unloaded) or replaced because its captured mesh changed.
     */
    private static final class BeEntry {
        int bx, by, bz;                          // block position (drives the per-frame instance transform)
        long meshHash;                           // hash of the captured mesh — rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window — for eviction
        float[] prevVerts;                       // block-local verts at this build, for the per-vertex MV diff
    }

    /** CPU-only capture state for one entity; the manager owns its opaque resident reference. */
    private static final class EntityState {
        long lastSeen;
        RtSceneGeometryManager.DeformingReference reference;
        float[] refVerts;
        int refVertCount = -1;
        int refIdxCount = -1;
        long refShadeHash;                      // rotation-invariant uv+prim hash (catches tint/sprite swaps)
        long retryYawFitAfter;
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

    private record Motion(float[] displacement, int displacementCount, float rigidX, float rigidY, float rigidZ) {
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
        final RtSceneGeometryManager.DynamicFrame geometry;
        final RtMaterialRegistry.Snapshot materials;
        int count;        // geometry-table entries / TLAS instances
        int logicalCount; // ordinary entities + block entities + individual particles

        FrameBuild(RtSceneGeometryManager.DynamicFrame geometry, RtMaterialRegistry.Snapshot materials) {
            this.geometry = geometry;
            this.materials = materials;
        }

        boolean full() {
            return logicalCount >= maxEntities();
        }
    }

    /**
     * Capture this frame's model entities, block entities, and particles into the manager-owned dynamic
     * suffix. Dynamic entity coordinates are local and placed by instances; particles remain captured
     * rebase-relative with an identity instance.
     */
    public void beginFrame(GpuContext ctx, RtSceneGeometryManager.DynamicFrame geometry,
                           int rbx, int rby, int rbz,
                           double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        RtMaterialRegistry registry = RtMaterialRegistry.INSTANCE;
        FrameBuild build = new FrameBuild(geometry, registry.requireSnapshot());
        activeGeometry = geometry;
        if (!enabled()) {
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
        evictStaleAccels(ctx);
        evictStaleBes(ctx);
        finishFrame(build);
    }

    private void finishFrame(FrameBuild build) {
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.uploadFlush")) {
            if (build.count != 0) RtFrameStats.FRAME.count("geometryTableFlushes", 1);
        }
    }

    /** Capture animated entities (mobs, items, falling blocks) with per-object motion-vector displacement. */
    private void captureEntities(GpuContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Entity cameraEntity = mc.getCameraEntity();
        // In first person the camera owner's own body must not block the primary camera ray, but it should
        // still appear in reflections / shadows / GI (so the player sees themselves in water as others would).
        // In F5 third person it renders fully, like any other entity.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        curVerts.clear();
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
            EntityPrev prev = prevVerts.get(id);
            capture.reset(prev != null ? prev.size / 3 : 0);
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
                // the camera every frame (see NameTagFeature), so — unlike glow, whose mesh is captured
                // straight into the SAME rigid entity mesh used for the BLAS — they are never mixed into
                // `capture`: doing so would make every frame's mesh a non-rigid transform of the last
                // whenever the camera turns, defeating rigid-reuse/motion-vector fitting for every
                // name-tagged entity. WorldOverlayPass renders them in a completely separate raster pass.
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
            // Motion vs last frame's posed mesh. New/topology-changed entities get one frame of camera-only
            // MV; rigid translation is packed into the table, deformation gets a disp buffer.
            Motion motion;
            long motionStart = RtFrameStats.FRAME.startStage();
            try {
                motion = captureVertexMotion(capture.verts, prev, ix, iy, iz);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.motion", motionStart);
            }
            curVerts.put(id, storeEntityPrev(prev, capture.verts, ix, iy, iz));
            // Rigid reuse first: a pose that is a rigid transform of the entity's last-built mesh
            // re-references that AS through the instance transform — no upload, no refit.
            boolean reused;
            long reuseStart = RtFrameStats.FRAME.startStage();
            try {
                reused = appendRigidReuse(ctx, build, motion, id, mask, ix - rbx, iy - rby, iz - rbz);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.rigidReuse", reuseStart);
            }
            if (!reused) {
                appendCapture(ctx, build, motion, id, mask,
                        translationTransform(ix - rbx, iy - rby, iz - rbz));
            }
            build.logicalCount++;
            RtFrameStats.FRAME.count("entitiesCaptured", 1);
            capturedThisFrame++;
        }
        Int2ObjectOpenHashMap<EntityPrev> oldPrev = prevVerts;
        prevVerts = curVerts;
        curVerts = oldPrev;
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
     * Capture this entity's world-space motion-vector displacement. Captures are entity-local, so the delta
     * is {@code (anchorCur + vertexCur) - (anchorPrev + vertexPrev)}. If every vertex agrees,
     * store it as a rigid vector in the geometry-table entry; otherwise write a per-vertex {@code vec4}
     * array for the geometry manager to upload with the submitted mesh.
     */
    private Motion captureVertexMotion(FloatArrayList cur, EntityPrev prev,
                                       float anchorX, float anchorY, float anchorZ) {
        if (prev == null || prev.size != cur.size()) {
            return NO_MOTION;
        }
        float[] curVerts = cur.elements();
        float[] prevVerts = prev.verts;
        float sx = anchorX - prev.anchorX;
        float sy = anchorY - prev.anchorY;
        float sz = anchorZ - prev.anchorZ;
        int vc = cur.size() / 3;
        if (vc == 0) {
            return NO_MOTION;
        }

        float dx0 = (curVerts[0] - prevVerts[0]) + sx;
        float dy0 = (curVerts[1] - prevVerts[1]) + sy;
        float dz0 = (curVerts[2] - prevVerts[2]) + sz;
        boolean rigid = true;
        for (int i = 1; i < vc; i++) {
            float dx = (curVerts[i * 3]     - prevVerts[i * 3])     + sx;
            float dy = (curVerts[i * 3 + 1] - prevVerts[i * 3 + 1]) + sy;
            float dz = (curVerts[i * 3 + 2] - prevVerts[i * 3 + 2]) + sz;
            if (Math.abs(dx - dx0) > RIGID_DISP_EPS
                    || Math.abs(dy - dy0) > RIGID_DISP_EPS
                    || Math.abs(dz - dz0) > RIGID_DISP_EPS) {
                rigid = false;
                break;
            }
        }
        if (rigid) {
            return new Motion(null, 0, dx0, dy0, dz0);
        }

        long bytes = (long) vc * 4L * Float.BYTES;
        float[] displacement = new float[vc * 4];
        for (int i = 0; i < vc; i++) {
            displacement[i * 4] = (curVerts[i * 3] - prevVerts[i * 3]) + sx;
            displacement[i * 4 + 1] = (curVerts[i * 3 + 1] - prevVerts[i * 3 + 1]) + sy;
            displacement[i * 4 + 2] = (curVerts[i * 3 + 2] - prevVerts[i * 3 + 2]) + sz;
        }
        RtFrameStats.FRAME.count("entityMotionUploadBytes", bytes);
        return new Motion(displacement, displacement.length, 0f, 0f, 0f);
    }

    private static EntityPrev storeEntityPrev(EntityPrev prev, FloatArrayList cur,
                                              float anchorX, float anchorY, float anchorZ) {
        EntityPrev out = prev != null ? prev : new EntityPrev();
        int size = cur.size();
        if (out.verts.length < size) {
            out.verts = new float[size];
        }
        System.arraycopy(cur.elements(), 0, out.verts, 0, size);
        out.size = size;
        out.anchorX = anchorX;
        out.anchorY = anchorY;
        out.anchorZ = anchorZ;
        return out;
    }

    /** Core per-vertex disp builder: {@code (cur − prev) + rebaseShift}, packed vec4/vertex (w = 0). */
    private static float[] buildDisp(float[] cur, int curSize, float[] prev, float sx, float sy, float sz) {
        int vc = curSize / 3;
        float[] d = new float[vc * 4];
        for (int i = 0; i < vc; i++) {
            d[i * 4]     = (cur[i * 3]     - prev[i * 3])     + sx;
            d[i * 4 + 1] = (cur[i * 3 + 1] - prev[i * 3 + 1]) + sy;
            d[i * 4 + 2] = (cur[i * 3 + 2] - prev[i * 3 + 2]) + sz;
            d[i * 4 + 3] = 0f;
        }
        return d;
    }

    /**
     * Capture this frame's billboard particles as one combined rebuilt mesh (cutout, camera-only receiver),
     * with per-particle motion vectors. We iterate the LIVE {@code Particle} objects (via accessor mixins)
     * rather than the public packed render state, because only the live objects carry stable identity —
     * needed to diff each particle's center against last frame for the MV. Each particle is extracted into
     * {@link #particleScratch} (its billboard quad), funneled through {@link #particleCapture} into the
     * shared {@code capture}, and its quad center cached by identity in {@link #particlePrev}. Per-layer
     * base-color texture index comes from the layer's atlas via the bindless registry. One
     * instance with mask {@link #PARTICLE_MASK} (primary-ray only).
     */
    private void captureParticles(GpuContext ctx, FrameBuild build, Minecraft mc, float partial,
                                  int rbx, int rby, int rbz, Matrix4f projection, Matrix4f viewRotation) {
        int particleLimit = maxParticles();
        if (!particlesEnabled() || particleLimit == 0 || build.full()) {
            particlePrev.clear();
            particleCur.clear();
            return;
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) {
            return;
        }
        Map<ParticleRenderType, ParticleGroup<?>> groups =
                ((ParticleEngineAccessor) mc.particleEngine).caustica$getParticleGroups();
        if (groups == null || groups.isEmpty()) {
            particlePrev.clear();
            particleCur.clear();
            return;
        }
        capture.reset();
        RtMaterialRegistry registry = RtMaterialRegistry.INSTANCE;
        // Billboards are thin two-sided scatterers, which their material says with a transmission weight;
        // every layer shares the Minecraft source's named material and pairs it with its own atlas slot.
        // The transparent border is absent geometry, so the producer derives cutout coverage from the
        // named binding captured in this resource epoch.
        int particleMaterial = build.materials.bindingId(MinecraftMaterialSource.PARTICLE_BILLBOARD);
        capture.currentMaterialId = registry.withCutoutCoverage(build.materials, particleMaterial);
        capture.currentSbtClass = registry.sbtClassFor(capture.currentMaterialId);
        particleDisp.clear();
        // extract() emits camera-relative positions; shift them into rebased space (identity instance).
        Vec3 camPos = cam.position();
        particleCapture.setOffset((float) (camPos.x - rbx), (float) (camPos.y - rby), (float) (camPos.z - rbz));
        // Reject particles whose world-space bounds are wholly outside before paying extract/build-layer
        // cost. The center test after extraction retains the existing exact inclusion behavior for bounds
        // which intersect the frustum.
        Frustum frustum = new Frustum(viewRotation, projection);
        frustum.prepare(camPos.x, camPos.y, camPos.z);
        IdentityHashMap<Particle, ParticlePrev> cur = particleCur;
        cur.clear();
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
                    appendParticleMv(p, particleCenterScratch, vertBefore, vertAfter, rbx, rby, rbz, cur);
                    build.logicalCount++;
                    particlesCaptured++;
                }
            }
        } catch (Throwable t) {
            capture.reset();
            particleDisp.clear();
            throw new RuntimeException("RT particle capture failed", t); // propagate to composite() (see entity path)
        }
        RtFrameStats.FRAME.count("particlesCaptured", particlesCaptured);
        IdentityHashMap<Particle, ParticlePrev> oldPrev = particlePrev;
        particlePrev = cur;
        particleCur = oldPrev;
        if (capture.isEmpty()) {
            return;
        }
        appendCapture(ctx, build, new Motion(particleDisp.elements(), particleDisp.size(), 0f, 0f, 0f),
                -1, PARTICLE_MASK, IDENTITY); // one combined mesh, per-particle MV
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
     * Compute one particle's motion-vector displacement (its quad center vs. last frame's, keyed by
     * identity) and write it for each of the particle's vertices into {@link #particleDisp}. All four
     * billboard verts share the center displacement (per-particle-rigid MV).
     */
    private void appendParticleMv(Particle p, float[] center, int vertBefore, int vertAfter,
                                  int rbx, int rby, int rbz, IdentityHashMap<Particle, ParticlePrev> cur) {
        ParticlePrev prev = particlePrev.remove(p);
        // World displacement = (curCenter − prevCenter) + (rebaseCur − rebasePrev). New particle ⇒ 0 (no MV).
        float dx = prev == null ? 0f : (center[0] - prev.cx) + (rbx - prev.rbx);
        float dy = prev == null ? 0f : (center[1] - prev.cy) + (rby - prev.rby);
        float dz = prev == null ? 0f : (center[2] - prev.cz) + (rbz - prev.rbz);
        for (int i = vertBefore; i < vertAfter; i++) {
            particleDisp.add(dx);
            particleDisp.add(dy);
            particleDisp.add(dz);
            particleDisp.add(0f);
        }
        if (prev == null) {
            prev = new ParticlePrev();
        }
        prev.set(center[0], center[1], center[2], rbx, rby, rbz);
        cur.put(p, prev);
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
        float[] disp = null; // static unless the mesh changed this frame (chest lid / spawner animation)
        if (entry == null || entry.meshHash != hash) {
            // Geometry changed (or new BE) → rebuild, but only within this frame's budget. Over budget: keep
            // showing the previous geometry; a brand-new BE simply pops in over the next frames.
            if (beBuildsThisFrame >= beBuildsPerFrame()) {
                if (entry != null) {
                    emitBe(ctx, build, entry, null, rbx, rby, rbz); // over budget: keep last geometry, no MV
                }
                return;
            }
            // Per-vertex MV from the previous build's block-local mesh (same vertex count ⇒ pairable).
            // The BE itself doesn't move, so the world displacement is the pure local delta.
            if (entry != null && entry.prevVerts != null && entry.prevVerts.length == capture.verts.size()) {
                disp = buildDisp(capture.verts.elements(), capture.verts.size(), entry.prevVerts, 0f, 0f, 0f);
            }
            BeEntry rebuilt = buildBe(build, be, hash);
            rebuilt.lastSeen = now;
            beCache.put(key, rebuilt);
            entry = rebuilt;
        }
        emitBe(ctx, build, entry, disp, rbx, rby, rbz);
    }

    /** Replaces the manager-owned cached resident for this block-local mesh. */
    private BeEntry buildBe(FrameBuild build, BlockEntity be, long hash) {
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        BlockPos p = be.getBlockPos();
        build.geometry.replaceCached(p.asLong(), packedInput(packed, RtSceneGeometryManager.BuildClass.STATIC));
        beBuildsThisFrame++;

        BeEntry e = new BeEntry();
        e.bx = p.getX();
        e.by = p.getY();
        e.bz = p.getZ();
        e.meshHash = hash;
        // Retain this build's block-local verts so the next rebuild can diff against them for the MV.
        e.prevVerts = java.util.Arrays.copyOf(capture.verts.elements(), capture.verts.size());
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

    /** Emit a cached block entity into this frame: its geometry-table entry + a TLAS instance (no GPU build). */
    private void emitBe(GpuContext ctx, FrameBuild build, BeEntry e, float[] disp, int rbx, int rby, int rbz) {
        if (build.full()) {
            return;
        }
        // disp is non-null only for a BE whose mesh changed this frame (chest lid / spawner); a static BE
        // passes null ⇒ no per-vertex motion. The manager-owned transient buffer is released with this frame,
        // animating reverts to MV 0 next frame.
        // Block-local mesh placed by a translate-only instance transform (blockPos − rebase), like terrain.
        float[] xform = {1, 0, 0, e.bx - rbx, 0, 1, 0, e.by - rby, 0, 0, 1, e.bz - rbz};
        build.geometry.appendCached(BlockPos.asLong(e.bx, e.by, e.bz),
                build.geometry.motion(disp, disp == null ? 0 : disp.length, 0f, 0f, 0f), xform, MASK_ALL);
        build.count++;
        build.logicalCount++;
        RtFrameStats.FRAME.count("blockEntitiesCaptured", 1);
    }

    /** Drop cached block entities not seen (in window) within the last KEEP_FRAMES frames — unloaded/out of view. */
    private void evictStaleBes(GpuContext ctx) {
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
            activeGeometry.releaseCached(BlockPos.asLong(e.bx, e.by, e.bz));
            it.remove();
        }
    }

    /**
     * Rigid-reuse fast path: if the current local-space {@link #capture} is identical to, or a rigid yaw
     * transform of, the mesh this entity's AS was last built from, emit a geometry-table entry pointing at
     * the cached shading buffers and a TLAS instance carrying the fitted transform over the cached AS —
     * skipping the 4 mesh uploads and the BLAS refit. Covers still mobs, item frames, armor stands, and
     * spinning/bobbing dropped items. The hit shader rotates prim normals / TBN by the instance transform,
     * so rotated instances shade correctly. Motion vectors are untouched: {@code motion} was already
     * computed against last frame's capture (a rotating pose gets its per-vertex disp buffer as usual).
     * Returns false (caller takes the full path) when there is no reusable AS, the topology changed, the
     * pose is non-rigid (animation), or the shading data changed under identical topology.
     */
    private boolean appendRigidReuse(GpuContext ctx, FrameBuild build, Motion motion, int entityId, int mask,
                                     float placeX, float placeY, float placeZ) {
        EntityState ea = entityStates.get(entityId);
        if (ea == null || ea.reference == null
                || ea.refVertCount != capture.verts.size() / 3 || ea.refIdxCount != capture.idx.size()) {
            return false;
        }
        long equalStart = RtFrameStats.FRAME.startStage();
        boolean equal;
        try {
            equal = positionsBitwiseEqual(ea.refVerts, capture.verts.elements(), ea.refVertCount * 3);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.equal", equalStart);
        }
        float[] localTransform = IDENTITY;
        if (!equal) {
            long now = RtComposite.frameCounter();
            if (now < ea.retryYawFitAfter) {
                return false;
            }
            long yawStart = RtFrameStats.FRAME.startStage();
            try {
                localTransform = fitYawTransform(ea.refVerts, capture.verts.elements(), ea.refVertCount);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.yaw", yawStart);
            }
            if (localTransform == null) {
                RtFrameStats.FRAME.count("entityRigidFitFailures", 1);
                ea.retryYawFitAfter = now + 8L;
                return false;
            }
            RtFrameStats.FRAME.count("entityRigidFitSuccesses", 1);
            ea.retryYawFitAfter = 0L;
        }
        // Same topology + rigid pose, but tint/sprite/material lanes may still have changed (dyed sheep,
        // item frame content swap that kept counts). Compare the rotation-invariant shading hash.
        long shadeStart = RtFrameStats.FRAME.startStage();
        try {
            if (shadeHash() != ea.refShadeHash) {
                return false;
            }
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.shade", shadeStart);
        }
        ea.lastSeen = RtComposite.frameCounter();
        build.geometry.appendDeformingReference(ea.reference, motionInput(build, motion),
                placeTransform(localTransform, placeX, placeY, placeZ), mask);
        build.count++;
        RtFrameStats.FRAME.count("entityReuse", 1);
        return true;
    }

    /**
     * Fit {@code cur ≈ R_yaw·ref + t} in entity-local space. Exact local equality is handled before this
     * method; this slower centroid/yaw fit covers dropped-item spin and minecarts. Pitch/roll and skeletal
     * deformation take the full path.
     */
    private static float[] fitYawTransform(float[] ref, float[] cur, int vc) {
        // Centroid-align, then the least-squares rotation angle about Y for
        // x' = x·cos + z·sin, z' = −x·sin + z·cos is atan2(Σ(cx·rz − cz·rx), Σ(cx·rx + cz·rz)).
        float crx = 0f, cry = 0f, crz = 0f, ccx = 0f, ccy = 0f, ccz = 0f;
        for (int i = 0; i < vc; i++) {
            crx += ref[i * 3];
            cry += ref[i * 3 + 1];
            crz += ref[i * 3 + 2];
            ccx += cur[i * 3];
            ccy += cur[i * 3 + 1];
            ccz += cur[i * 3 + 2];
        }
        float inv = 1f / vc;
        crx *= inv; cry *= inv; crz *= inv;
        ccx *= inv; ccy *= inv; ccz *= inv;
        double a = 0.0, b = 0.0;
        for (int i = 0; i < vc; i++) {
            float rx = ref[i * 3] - crx, rz = ref[i * 3 + 2] - crz;
            float cx = cur[i * 3] - ccx, cz = cur[i * 3 + 2] - ccz;
            a += (double) cx * rx + (double) cz * rz;
            b += (double) cx * rz - (double) cz * rx;
        }
        float cos = (float) Math.cos(Math.atan2(b, a));
        float sin = (float) Math.sin(Math.atan2(b, a));
        for (int i = 0; i < vc; i++) {
            float rx = ref[i * 3] - crx, ry = ref[i * 3 + 1] - cry, rz = ref[i * 3 + 2] - crz;
            float ex = (rx * cos + rz * sin) - (cur[i * 3] - ccx);
            float ey = ry - (cur[i * 3 + 1] - ccy);
            float ez = (-rx * sin + rz * cos) - (cur[i * 3 + 2] - ccz);
            if (Math.abs(ex) > RIGID_FIT_EPS || Math.abs(ey) > RIGID_FIT_EPS || Math.abs(ez) > RIGID_FIT_EPS) {
                return null;
            }
        }
        // p_out = R·(p − cr) + cc  ⇒  t = cc − R·cr.
        float rcx = crx * cos + crz * sin;
        float rcz = -crx * sin + crz * cos;
        return new float[] {
                cos, 0, sin, ccx - rcx,
                0,   1, 0,   ccy - cry,
                -sin, 0, cos, ccz - rcz};
    }

    private static boolean positionsBitwiseEqual(float[] a, float[] b, int size) {
        for (int i = 0; i < size; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static float[] translationTransform(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    private static float[] placeTransform(float[] local, float x, float y, float z) {
        if (local == IDENTITY) {
            return translationTransform(x, y, z);
        }
        return new float[] {
                local[0], local[1], local[2], local[3] + x,
                local[4], local[5], local[6], local[7] + y,
                local[8], local[9], local[10], local[11] + z};
    }

    /**
     * FNV-1a over the capture's shading data that must match for AS reuse: UVs plus each prim record's
     * emission/tint/material lanes. Prim NORMALS are deliberately excluded — they rotate with the pose,
     * and the hit shader re-rotates the cached ones via the instance transform.
     */
    private long shadeHash() {
        long h = 1469598103934665603L;
        float[] uv = capture.uvList.elements();
        int un = capture.uvList.size();
        for (int i = 0; i < un; i++) {
            h = (h ^ (Float.floatToRawIntBits(uv[i]) & 0xffffffffL)) * 1099511628211L;
        }
        float[] pr = capture.prim.elements();
        int pn = capture.prim.size();
        for (int base = 0; base < pn; base += 12) {
            for (int k = 3; k < 12; k++) { // skip normal.xyz (0..2); keep emission(3), tint(4..7), mat(8..11)
                h = (h ^ (Float.floatToRawIntBits(pr[base + k]) & 0xffffffffL)) * 1099511628211L;
            }
        }
        int[] classes = capture.sbtClasses.elements();
        for (int i = 0; i < capture.sbtClasses.size(); i++) {
            h = (h ^ (classes[i] & 0xffffffffL)) * 1099511628211L;
        }
        return h;
    }

    private void appendCapture(GpuContext ctx, FrameBuild build, Motion motion, int entityId, int mask,
                               float[] instanceTransform) {
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        RtSceneGeometryManager.PackedInput input = packedInput(packed,
                entityId < 0 ? RtSceneGeometryManager.BuildClass.REBUILT : RtSceneGeometryManager.BuildClass.DEFORMING);
        RtSceneGeometryManager.MotionInput motionInput = motionInput(build, motion);
        if (entityId < 0) {
            build.geometry.appendRebuilt(input, motionInput, instanceTransform, mask);
        } else {
            EntityState state = entityStates.computeIfAbsent(entityId, unused -> new EntityState());
            state.lastSeen = RtComposite.frameCounter();
            state.reference = build.geometry.appendDeforming(entityId, input, motionInput, instanceTransform, mask,
                    CausticaConfig.Rt.Entities.REFIT_ENABLED.value());
            int size = capture.verts.size();
            if (state.refVerts == null || state.refVerts.length < size) state.refVerts = new float[size];
            System.arraycopy(capture.verts.elements(), 0, state.refVerts, 0, size);
            state.refVertCount = size / 3;
            state.refIdxCount = packed.indices().size();
            state.refShadeHash = shadeHash();
        }
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

    private RtSceneGeometryManager.MotionInput motionInput(FrameBuild build, Motion motion) {
        return build.geometry.motion(motion.displacement, motion.displacementCount,
                motion.rigidX, motion.rigidY, motion.rigidZ);
    }

    private void evictStaleAccels(GpuContext ctx) {
        long now = RtComposite.frameCounter();
        var it = entityStates.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().lastSeen < KEEP_FRAMES) continue;
            activeGeometry.releaseDeforming(entry.getIntKey());
            it.remove();
        }
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
        // Material invalidation retires manager-owned residents on the next frame. Drop every opaque
        // reference and source-side mesh hash now so no old material epoch can be reused meanwhile.
        entityStates.clear();
        beCache.clear();
        prevVerts.clear();
        curVerts.clear();
        particlePrev.clear();
        particleCur.clear();
        particleDisp.clear();
        collector.clearCaches();
    }

    /** Clear capture state; the geometry manager owns and tears down all GPU residents. */
    public void shutdown(GpuContext ctx) {
        if (activeGeometry != null) {
            for (int id : entityStates.keySet()) activeGeometry.releaseDeforming(id);
            for (long key : beCache.keySet()) activeGeometry.releaseCached(key);
        }
        entityStates.clear();
        beCache.clear();
        prevVerts.clear();
        curVerts.clear();
        particlePrev.clear();
        particleCur.clear();
        particleDisp.clear();
        glowBatches.clear();
        nameTagBatches.clear();
        resetPoseStack(blockEntityPoseStack);
        beCandidates.clear();
        beCandidatePool.clear();
        activeGeometry = null;
        collector.clearCaches();
    }
}
