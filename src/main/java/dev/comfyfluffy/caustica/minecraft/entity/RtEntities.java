package dev.comfyfluffy.caustica.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.ParticleEngineAccessor;
import dev.comfyfluffy.caustica.mixin.ParticleGroupAccessor;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.minecraft.MinecraftTelemetry;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
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
import java.util.Set;
import java.util.UUID;

/**
 * Dynamic entities as real ray-traced {@code ModelPart} geometry. Each frame, every model entity is
 * re-posed and captured ({@link RtEntityCollector} + {@link RtEntityCapture}) into neutral scene meshes.
 * The engine-owned scene geometry manager owns their resident GPU geometry, acceleration
 * structures, table records, instances, and graphics lifetime. This source retains only Minecraft
 * capture state, mesh change detection, and stale-entry eviction state.
 * Non-model entities (items/arrows — geometry via submitItem/submitBlockModel, which the collector
 * ignores) are skipped.
 *
 * <p>Per-frame capture is capped by {@code -Dcaustica.rt.maxEntities}. The geometry manager derives
 * compatible acceleration updates from retained mesh topology.
 */
public final class RtEntities {
    public static final RtEntities INSTANCE = new RtEntities();
    private static final long ENTITY_GEOMETRY = 1L;
    private static final long BLOCK_ENTITY_GEOMETRY = 2L;
    private static final long PARTICLE_GEOMETRY = 3L;
    private static final long ENTITY_TRANSFORM = 4L;
    private static final long ENTITY_LIFECYCLE = 5L;
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
    private final Set<SceneGeometryKey> pendingDrops = new java.util.LinkedHashSet<>();

    private RtEntities() {
    }

    /**
     * Cached block-entity geometry. The mesh is captured in <b>block-local</b> space (identity submit pose),
     * so the retained world-space placement remains valid across scene-origin rebases.
     */
    private static final class BeEntry {
        int bx, by, bz;                          // block position used to remove the retained resident
        long meshHash;                           // hash of the captured mesh — rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window — for eviction
    }

    /** CPU-only capture state for one entity; the manager owns its resident. */
    static final class EntityState {
        final UUID identity;
        long lastSeen;
        long meshHash;
        boolean initialSubmitted;
        long profiledMeshVersion;
        long visibleMeshVersion;
        long meshVisibilityCount;
        long meshRevisionsSkippedBetweenVisibility;
        long meshVisibilityIntervalFramesTotal;
        long meshVisibilityIntervalFramesMax;
        long initialUnavailableFrames;
        long priorRevisionInterveningFramesAtReplacementTotal;
        long priorRevisionInterveningFramesAtReplacementMax;
        long lastMeshVisibilityFrame = -1L;
        long visibleMeshSourceFrame = -1L;
        long pendingVisibleMeshVersion;
        long pendingVisibleMeshSourceFrame;
        long meshVisibilityToken;
        long pendingMeshVisibilityToken;
        boolean meshVisibilityQueued;
        EntityState(UUID identity) {
            this.identity = identity;
        }

        boolean requiresPut(long capturedMeshHash) {
            return meshHash != capturedMeshHash;
        }

        void meshSubmitted(long capturedMeshHash) {
            meshHash = capturedMeshHash;
            initialSubmitted = true;
        }

        boolean beginInitialSubmission(long capturedMeshHash) {
            if (initialSubmitted) return false;
            meshSubmitted(capturedMeshHash);
            return true;
        }

        long profileMeshSubmission() {
            return ++profiledMeshVersion;
        }

        void meshPublicationAccepted(long version, long sourceFrame, long token,
                                     MinecraftTelemetry.Instrumentation telemetry) {
            if (token != meshVisibilityToken) return;
            pendingVisibleMeshVersion = version;
            pendingVisibleMeshSourceFrame = sourceFrame;
            if (!meshVisibilityQueued) {
                meshVisibilityQueued = true;
                pendingMeshVisibilityToken = token;
                telemetry.afterPublicationVisible(frame -> meshFrameVisible(frame, token, telemetry));
            }
        }

        void invalidateMeshVisibility() {
            meshVisibilityToken++;
            meshVisibilityQueued = false;
        }

        void meshFrameVisible(long frame, long token, MinecraftTelemetry.Instrumentation telemetry) {
            if (!meshVisibilityQueued || token != meshVisibilityToken || token != pendingMeshVisibilityToken) return;
            meshVisibilityQueued = false;
            long version = pendingVisibleMeshVersion;
            long sourceFrame = pendingVisibleMeshSourceFrame;
            telemetry.count("entityMeshVisibilitySamples", 1);
            if (meshVisibilityCount == 0L) {
                long unavailable = Math.max(0L, frame - sourceFrame);
                initialUnavailableFrames = unavailable;
                telemetry.count("entityMeshInitialUnavailableFramesTotal", unavailable);
                telemetry.count("entityMeshInitialUnavailableFramesSamples", 1);
                telemetry.max("entityMeshInitialUnavailableFramesMax", unavailable);
            } else {
                long skipped = Math.max(0L, version - visibleMeshVersion - 1L);
                long interval = Math.max(0L, frame - lastMeshVisibilityFrame);
                long interveningFrames = Math.max(0L, frame - visibleMeshSourceFrame - 1L);
                meshRevisionsSkippedBetweenVisibility += skipped;
                meshVisibilityIntervalFramesTotal += interval;
                meshVisibilityIntervalFramesMax = Math.max(meshVisibilityIntervalFramesMax, interval);
                priorRevisionInterveningFramesAtReplacementTotal += interveningFrames;
                priorRevisionInterveningFramesAtReplacementMax = Math.max(
                        priorRevisionInterveningFramesAtReplacementMax, interveningFrames);
                telemetry.count("entityMeshRevisionsSkippedBetweenVisibility", skipped);
                telemetry.count("entityMeshVisibilityIntervalFramesTotal", interval);
                telemetry.count("entityMeshVisibilityIntervalFramesSamples", 1);
                telemetry.max("entityMeshVisibilityIntervalFramesMax", interval);
                telemetry.count("entityMeshPriorRevisionInterveningFramesAtReplacementTotal",
                        interveningFrames);
                telemetry.count("entityMeshPriorRevisionInterveningFramesAtReplacementSamples", 1);
                telemetry.max("entityMeshPriorRevisionInterveningFramesAtReplacementMax",
                        interveningFrames);
            }
            visibleMeshVersion = version;
            visibleMeshSourceFrame = sourceFrame;
            lastMeshVisibilityFrame = frame;
            meshVisibilityCount++;
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
        final SceneGeometrySink geometry;
        final SceneOrigin origin;
        final long frameIndex;
        final MinecraftTelemetry.Instrumentation telemetry;
        int count;        // geometry-table entries / TLAS instances
        int logicalCount; // ordinary entities + block entities + individual particles

        FrameBuild(SceneGeometrySink geometry, SceneOrigin origin, long frameIndex,
                   MinecraftTelemetry.Instrumentation telemetry) {
            this.geometry = geometry;
            this.origin = origin;
            this.frameIndex = frameIndex;
            this.telemetry = telemetry;
        }

        void submit(SceneGeometryKey key, List<SceneGeometrySink.Operation> operations, Runnable acknowledgment) {
            pendingDrops.remove(key);
            int putCount = 0;
            int transformCount = 0;
            for (SceneGeometrySink.Operation operation : operations) {
                if (operation instanceof SceneGeometrySink.Put) {
                    putCount++;
                } else if (operation instanceof SceneGeometrySink.Transform) {
                    transformCount++;
                }
            }
            int sampleCount = putCount + transformCount;
            MinecraftTelemetry.GeometrySource kind = transformCount == 0
                    ? sourceKind(key) : MinecraftTelemetry.GeometrySource.ENTITY_PLACEMENT;
            Object extraction = sampleCount == 0 ? null : telemetry.extraction(kind, sampleCount);
            geometry.submit(key, operations, () -> {
                telemetry.published(extraction);
                if (acknowledgment != null) acknowledgment.run();
            });
        }

        boolean full() {
            return logicalCount >= maxEntities();
        }
    }

    /**
     * Capture this frame's model entities, block entities, and particles into independent source groups.
     * Dynamic entity coordinates are local and placed by instances; particles remain captured relative to
     * the authored scene origin with an identity instance.
     */
    public void submitGeometry(SceneFrameContext frame) {
        SceneOrigin origin = new SceneOrigin(frame.originX(), frame.originY(), frame.originZ());
        var camera = frame.camera();
        beginFrame(frame.geometry(), origin,
                camera.x(), camera.y(), camera.z(), new Matrix4f().set(camera.projection()),
                new Matrix4f().set(camera.viewRotation()), frame.frameIndex());
    }

    private void beginFrame(SceneGeometrySink geometry, SceneOrigin origin,
                           double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation,
                           long frameIndex) {
        FrameBuild build = new FrameBuild(geometry, origin, frameIndex, MinecraftTelemetry.current());
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

        try {
            long entityStart = build.telemetry.startStage();
            try {
                captureEntities(build, mc, level, partial, rbx, rby, rbz);
            } finally {
                build.telemetry.endStage("entity.capture", entityStart);
            }
            long blockEntityStart = build.telemetry.startStage();
            try {
                captureBlockEntities(build, mc, level, partial, rbx, rby, rbz);
            } finally {
                build.telemetry.endStage("entity.blockEntities", blockEntityStart);
            }
            long particleStart = build.telemetry.startStage();
            try {
                captureParticles(build, mc, partial, rbx, rby, rbz, projection, viewRotation);
            } finally {
                build.telemetry.endStage("entity.particles", particleStart);
            }
        } catch (RuntimeException | Error t) {
            shutdown();
            throw t;
        }
        evictStaleAccels(build);
        evictStaleBes(build);
        finishFrame(build);
    }

    private void finishFrame(FrameBuild build) {
        List<SceneGeometryKey> drops = List.copyOf(pendingDrops);
        pendingDrops.clear();
        for (SceneGeometryKey key : drops) {
            if (key.domain() == ENTITY_GEOMETRY) {
                submitEntityRemoval(build, key, null);
            } else {
                build.submit(key, List.of(new SceneGeometrySink.Remove(key), new SceneGeometrySink.Drop(key)), null);
            }
        }
    }

    /** Capture visible model entities (mobs, items, falling blocks). */
    private void captureEntities(FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
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
                long extractStart = build.telemetry.startStage();
                try {
                    state = dispatcher.extractEntity(entity, partial);
                } finally {
                    build.telemetry.endStage("entity.capture.extract", extractStart);
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
                long submitStart = build.telemetry.startStage();
                try {
                    dispatcher.submit(state, cameraState, 0.0, 0.0, 0.0, entityPoseStack, collector);
                } finally {
                    build.telemetry.endStage("entity.capture.submit", submitStart);
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
            appendCapture(build, id, entity.getUUID(), mask,
                    translationTransform(ix - rbx, iy - rby, iz - rbz));
            build.logicalCount++;
            build.telemetry.count("entitiesCaptured", 1);
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
    private void captureParticles(FrameBuild build, Minecraft mc, float partial,
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
        capture.currentMaterial = new SceneMesh.NamedMaterial(
                new dev.comfyfluffy.caustica.api.provider.MaterialHandle(MinecraftMaterialSource.PARTICLE_BILLBOARD));
        capture.currentCoverage = SceneMesh.Coverage.CUTOUT;
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
                    int ub = capture.uvList.size(), surfaceCount = capture.surfaces.size();
                    int vertBefore = vb / 3;
                    particleScratch.clear();
                    sq.extract(particleScratch, cam, partial);
                    for (SingleQuadParticle.Layer layer : particleScratch.layers()) {
                        RtEntityTextures.INSTANCE.contributeAtlas(layer.textureAtlasLocation());
                        capture.currentMaterial = new SceneMesh.NamedMaterial(
                                new dev.comfyfluffy.caustica.api.provider.MaterialHandle(MinecraftMaterialSource.PARTICLE_BILLBOARD),
                                new SceneMesh.AtlasTexture(ResourceId.of(
                                        layer.textureAtlasLocation().getNamespace(), layer.textureAtlasLocation().getPath())));
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
                        capture.surfaces.subList(surfaceCount, capture.surfaces.size()).clear();
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
        build.telemetry.count("particlesCaptured", particlesCaptured);
        if (capture.isEmpty()) {
            submitParticles(build);
            return;
        }
        submitParticles(build);
    }

    /** Replaces the one merged particle resident, or removes it when the capture is empty. */
    private void submitParticles(FrameBuild build) {
        List<SceneGeometrySink.Operation> operations;
        SceneGeometryKey particleKey = key(PARTICLE_GEOMETRY, PARTICLE_KEY);
        if (capture.isEmpty()) {
            operations = List.of(new SceneGeometrySink.Remove(particleKey),
                    new SceneGeometrySink.Drop(particleKey));
        } else {
            operations = List.of(
                    new SceneGeometrySink.Put(particleKey, capture.sceneMesh()),
                    new SceneGeometrySink.Place(particleKey, particleKey, transform(IDENTITY, build.origin), PARTICLE_MASK));
        }
        build.submit(particleKey, operations, null);
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
    private void captureBlockEntities(FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        beBuildsThisFrame = 0;
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        beDispatcher.prepare(cameraState.pos); // sets the camera for shouldRender / extract
        long now = build.frameIndex;
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
            updateBlockEntity(build, beDispatcher, candidate.be, partial, now);
        }
    }

    /** Re-mesh one block entity and submit only an initial or changed resident within the update budget. */
    private void updateBlockEntity(FrameBuild build, BlockEntityRenderDispatcher beDispatcher,
                                   BlockEntity be, float partial, long now) {
        capture.reset();
        try {
            BlockEntityRenderState state = beDispatcher.tryExtractRenderState(be, partial, null, false);
            if (state == null) {
                return; // off-screen-only (beacon/end-gateway), distance-culled, or no renderer
            }
            collector.begin(capture, false);
            // Identity pose keeps the retained mesh block-local; its initial Place owns the stable world position.
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
        if (entry == null || entry.meshHash != hash) {
            // Geometry changed (or new BE) → rebuild, but only within this frame's budget. Over budget: keep
            // showing the previous geometry; a brand-new BE simply pops in over the next frames.
            if (beBuildsThisFrame >= beBuildsPerFrame()) {
                if (entry != null) {
                    recordVisibleBlockEntity(build);
                }
                build.telemetry.count("blockEntityGeometryDeferred", 1);
                return;
            }
            BeEntry rebuilt = buildBe(build, entry, be, hash);
            rebuilt.lastSeen = now;
            beCache.put(key, rebuilt);
            recordVisibleBlockEntity(build);
            return;
        }
        recordVisibleBlockEntity(build);
    }

    /** Submits a keyed dynamic resident so compatible block-entity mesh updates retain vertex history. */
    private BeEntry buildBe(FrameBuild build, BeEntry entry, BlockEntity be, long hash) {
        BlockPos p = be.getBlockPos();
        beBuildsThisFrame++;

        boolean initial = entry == null;
        BeEntry e = entry != null ? entry : new BeEntry();
        e.bx = p.getX();
        e.by = p.getY();
        e.bz = p.getZ();
        e.meshHash = hash;
        long key = p.asLong();
        SceneGeometryKey geometryKey = key(BLOCK_ENTITY_GEOMETRY, key);
        build.submit(geometryKey, blockEntityMeshUpdate(geometryKey, capture.sceneMesh(),
                GeometryTransform.translation(p.getX(), p.getY(), p.getZ()), initial), null);
        build.telemetry.count("blockEntityGeometrySubmissions", 1);
        return e;
    }

    static List<SceneGeometrySink.Operation> blockEntityMeshUpdate(SceneGeometryKey key, SceneMesh mesh,
                                                                   GeometryTransform initialTransform,
                                                                   boolean initial) {
        SceneGeometrySink.Put put = new SceneGeometrySink.Put(key, mesh);
        return initial ? List.of(put, new SceneGeometrySink.Place(key, key, initialTransform, MASK_ALL)) : List.of(put);
    }

    /** FNV-1a hash of every manager-visible field in the currently captured mesh. */
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
        float[] uv = capture.uvList.elements();
        int uvn = capture.uvList.size();
        for (int i = 0; i < uvn; i++) {
            h = (h ^ (Float.floatToRawIntBits(uv[i]) & 0xffffffffL)) * 1099511628211L;
        }
        for (SceneMesh.TriangleSurface surface : capture.surfaces) {
            h = (h ^ surface.hashCode()) * 1099511628211L;
        }
        return h;
    }

    /** Counts one selected cached block entity without resubmitting its unchanged retained state. */
    private static void recordVisibleBlockEntity(FrameBuild build) {
        build.count++;
        build.logicalCount++;
        build.telemetry.count("blockEntitiesCaptured", 1);
    }

    /** Drop cached block entities not seen (in window) within the last KEEP_FRAMES frames — unloaded/out of view. */
    private void evictStaleBes(FrameBuild build) {
        if (beCache.isEmpty()) {
            return;
        }
        long now = build.frameIndex;
        Iterator<Map.Entry<Long, BeEntry>> it = beCache.entrySet().iterator();
        while (it.hasNext()) {
            BeEntry e = it.next().getValue();
            if (now - e.lastSeen < KEEP_FRAMES) {
                continue;
            }
            long key = BlockPos.asLong(e.bx, e.by, e.bz);
            SceneGeometryKey geometryKey = key(BLOCK_ENTITY_GEOMETRY, key);
            build.submit(geometryKey, List.of(
                    new SceneGeometrySink.Remove(geometryKey), new SceneGeometrySink.Drop(geometryKey)), null);
            it.remove();
        }
    }

    private static float[] translationTransform(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    private static SceneGeometryKey key(long domain, long value) {
        return new SceneGeometryKey(domain, value);
    }

    private static MinecraftTelemetry.GeometrySource sourceKind(SceneGeometryKey key) {
        if (key.domain() == BLOCK_ENTITY_GEOMETRY) {
            return MinecraftTelemetry.GeometrySource.BLOCK_ENTITY;
        }
        if (key.domain() == PARTICLE_GEOMETRY) {
            return MinecraftTelemetry.GeometrySource.PARTICLE;
        }
        return MinecraftTelemetry.GeometrySource.ENTITY;
    }

    private static GeometryTransform transform(float[] relative, SceneOrigin origin) {
        return new GeometryTransform(relative[0], relative[1], relative[2],
                relative[4], relative[5], relative[6], relative[8], relative[9], relative[10],
                origin.x() + relative[3], origin.y() + relative[7], origin.z() + relative[11]);
    }

    private void appendCapture(FrameBuild build, int entityId, UUID identity, int mask,
                               float[] instanceTransform) {
        SceneGeometryKey key = key(ENTITY_GEOMETRY, Integer.toUnsignedLong(entityId));
        EntityState state = entityStates.get(entityId);
        if (state != null && !state.identity.equals(identity)) {
            submitEntityRemoval(build, key, state);
            state = null;
        }
        if (state == null) {
            state = new EntityState(identity);
            entityStates.put(entityId, state);
        }
        state.lastSeen = build.frameIndex;
        pendingDrops.remove(key);
        GeometryTransform transform = transform(instanceTransform, build.origin);
        long capturedMeshHash = meshHash();
        if (state.beginInitialSubmission(capturedMeshHash)) {
            build.telemetry.count("entityPlacementInitialSubmissions", 1);
            EntityState submitted = state;
            List<SceneGeometrySink.Operation> operations = List.of(
                    new SceneGeometrySink.Put(key, capture.sceneMesh()),
                    new SceneGeometrySink.Place(key, key, transform, mask));
            if (build.telemetry.enabled()) {
                long version = state.profileMeshSubmission();
                long sourceFrame = build.telemetry.frameSerial();
                long visibilityToken = state.meshVisibilityToken;
                MinecraftTelemetry.Instrumentation telemetry = build.telemetry;
                build.submit(key, operations,
                        () -> submitted.meshPublicationAccepted(version, sourceFrame, visibilityToken, telemetry));
            } else {
                build.submit(key, operations, null);
            }
        } else {
            build.telemetry.count("entityPlacementFreshnessEligible", 1);
            build.submit(key(ENTITY_TRANSFORM, Integer.toUnsignedLong(entityId)),
                    List.of(new SceneGeometrySink.Transform(key, transform, mask)), null);
            if (state.requiresPut(capturedMeshHash)) {
                state.meshSubmitted(capturedMeshHash);
                build.telemetry.count("entityMeshOnlyUpdates", 1);
                if (build.telemetry.enabled()) {
                    long version = state.profileMeshSubmission();
                    long sourceFrame = build.telemetry.frameSerial();
                    long visibilityToken = state.meshVisibilityToken;
                    EntityState submitted = state;
                    MinecraftTelemetry.Instrumentation telemetry = build.telemetry;
                    build.submit(key, List.of(new SceneGeometrySink.Put(key, capture.sceneMesh())),
                            () -> submitted.meshPublicationAccepted(version, sourceFrame, visibilityToken, telemetry));
                } else {
                    build.submit(key, List.of(new SceneGeometrySink.Put(key, capture.sceneMesh())), null);
                }
            }
        }
        build.count++;
    }

    private void evictStaleAccels(FrameBuild build) {
        long now = build.frameIndex;
        var it = entityStates.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().lastSeen < KEEP_FRAMES) continue;
            int id = entry.getIntKey();
            SceneGeometryKey key = key(ENTITY_GEOMETRY, Integer.toUnsignedLong(id));
            submitEntityRemoval(build, key, entry.getValue());
            it.remove();
        }
    }

    private void submitEntityRemoval(FrameBuild build, SceneGeometryKey key, EntityState retiring) {
        build.submit(key(ENTITY_LIFECYCLE, key.value()), List.of(
                new SceneGeometrySink.Remove(key), new SceneGeometrySink.Drop(key)),
                retiring != null ? retiring::invalidateMeshVisibility : null);
    }

    /** Remove every source-owned resident when this capture path is disabled. */
    private void clearResidents(FrameBuild build) {
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
        for (int id : entityStates.keySet()) {
            SceneGeometryKey key = key(ENTITY_GEOMETRY, Integer.toUnsignedLong(id));
            submitEntityRemoval(build, key, null);
        }
        for (long value : beCache.keySet()) {
            SceneGeometryKey key = key(BLOCK_ENTITY_GEOMETRY, value);
            build.submit(key, List.of(
                    new SceneGeometrySink.Remove(key), new SceneGeometrySink.Drop(key)), null);
        }
        SceneGeometryKey particleKey = key(PARTICLE_GEOMETRY, PARTICLE_KEY);
        build.submit(particleKey, List.of(
                new SceneGeometrySink.Remove(particleKey), new SceneGeometrySink.Drop(particleKey)), null);
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
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
        for (int id : entityStates.keySet()) pendingDrops.add(key(ENTITY_GEOMETRY, Integer.toUnsignedLong(id)));
        for (long value : beCache.keySet()) pendingDrops.add(key(BLOCK_ENTITY_GEOMETRY, value));
        pendingDrops.add(key(PARTICLE_GEOMETRY, PARTICLE_KEY));
        entityStates.clear();
        beCache.clear();
        collector.clearCaches();
    }

    /** Prevent deferred profiling callbacks from outliving this provider's retained-scene ownership. */
    public void onSourceStopped() {
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
    }

    /** Clears this source's published snapshot before entity IDs can be reused by a new world. */
    public void onWorldChanged() {
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
        entityStates.clear();
        beCache.clear();
        pendingDrops.clear();
        collector.clearCaches();
    }

    /** Clear capture state; the geometry manager owns and tears down all GPU residents. */
    public void shutdown() {
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
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
