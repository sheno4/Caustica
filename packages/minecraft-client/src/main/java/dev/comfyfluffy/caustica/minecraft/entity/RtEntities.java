package dev.comfyfluffy.caustica.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.ParticleEngineAccessor;
import dev.comfyfluffy.caustica.mixin.ParticleGroupAccessor;
import dev.comfyfluffy.caustica.minecraft.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import dev.comfyfluffy.caustica.settings.ResourceId;
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
 * {@link MinecraftEntityGeometry} owns their retained mesh and instance identities while the uploader
 * owns source buffers until their introducing retained batches retire. This producer retains Minecraft
 * capture state, mesh change detection, and stale-entry eviction state.
 * Non-model entities (items/arrows — geometry via submitItem/submitBlockModel, which the collector
 * ignores) are skipped.
 *
 * <p>Per-frame capture is capped by {@code -Dcaustica.rt.maxEntities}. Stable index revisions allow the
 * retained backend to derive compatible acceleration updates.
 */
public final class RtEntities implements dev.comfyfluffy.caustica.minecraft.MinecraftEntityCaptureBinding {
    private static final long ENTITY_GEOMETRY = 1L;
    private static final long BLOCK_ENTITY_GEOMETRY = 2L;
    private static final long PARTICLE_GEOMETRY = 3L;
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
    // the retained owner replaces GPU geometry only when it changed, so static
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
    private final RtEntityTextures textures;
    private final RtEntityCollector collector;
    private final MinecraftTelemetry.Instrumentation instrumentation;
    private final RtEntityCapture capture = new RtEntityCapture();
    private final PoseStack entityPoseStack = new PoseStack();
    private final PoseStack blockEntityPoseStack = new PoseStack();
    private CameraRenderState cameraState;
    // Particle capture funnels MC billboard quads into the shared entity mesh. Rebuilt particle topology
    // has no stable vertex correspondence, so each upload carries no cross-frame vertex history.
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

    // CPU capture state for directly retained dynamic residents.
    private final Int2ObjectOpenHashMap<EntityState> entityStates = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // Persistent per-block-entity placement state, keyed by BlockPos.asLong(). Unchanged captured mesh
    // revisions reuse that resident's retained mesh through MinecraftEntityGeometry.
    private final Map<Long, BeEntry> beCache = new HashMap<>();
    private final List<BeCandidate> beCandidates = new ArrayList<>();
    private final ArrayDeque<BeCandidate> beCandidatePool = new ArrayDeque<>();
    // (Re)builds recorded so far this frame, reset each beginFrame; gates new BE builds to BE_BUILDS_PER_FRAME.
    private int beBuildsThisFrame;
    private long meshRevisionEpoch;
    private final Set<MinecraftEntityGeometry.Key> pendingDrops = new java.util.LinkedHashSet<>();
    private MinecraftEntityGeometry geometry;
    private GeometryPublication submittedPublication;

    public RtEntities(RtEntityTextures textures, MinecraftTelemetry.Instrumentation instrumentation) {
        this.textures = java.util.Objects.requireNonNull(textures, "textures");
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation, "instrumentation");
        collector = new RtEntityCollector(textures, instrumentation);
    }

    public synchronized void bindGeometry(MinecraftEntityGeometry geometry) {
        if (this.geometry != null) throw new IllegalStateException("entity geometry is already bound");
        this.geometry = java.util.Objects.requireNonNull(geometry, "geometry");
    }

    @Override public synchronized Lease install(MinecraftEntityGeometry geometry) {
        bindGeometry(geometry);
        return () -> uninstall(geometry);
    }

    private synchronized void uninstall(MinecraftEntityGeometry installed) {
        if (geometry != installed) return;
        geometry = null;
        shutdown();
    }

    public synchronized void unbindGeometry(MinecraftEntityGeometry geometry) {
        if (this.geometry == geometry) this.geometry = null;
    }

    /**
     * Cached block-entity revision and placement. Meshes are captured in <b>block-local</b> space
     * (identity submit pose), so an unchanged resident can reuse its BLAS across placement updates.
     */
    private static final class BeEntry {
        int bx, by, bz;                          // block position used to remove the retained resident
        long meshHash;                           // hash of the captured mesh — rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window — for eviction
    }

    /** CPU-only capture state for one entity; MinecraftEntityGeometry owns its resident. */
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
        final MinecraftEntityGeometry geometry;
        final SceneOrigin origin;
        final long frameIndex;
        final MinecraftTelemetry.Instrumentation telemetry;
        int count;        // retained scene instances
        int logicalCount; // ordinary entities + block entities + individual particles

        FrameBuild(MinecraftEntityGeometry geometry, SceneOrigin origin, long frameIndex,
                   MinecraftTelemetry.Instrumentation telemetry) {
            this.geometry = geometry;
            this.origin = origin;
            this.frameIndex = frameIndex;
            this.telemetry = telemetry;
        }

        void put(MinecraftEntityGeometry.Key key, MinecraftEntityMesh mesh, GeometryTransform transform,
                 MinecraftEntityGeometry.MeshRevision revision, int mask, Runnable acknowledgment) {
            pendingDrops.remove(key);
            Object extraction = telemetry.extraction(sourceKind(key), 1);
            geometry.put(key, revision, mesh, transform, mask);
            telemetry.published(extraction);
            if (acknowledgment != null) acknowledgment.run();
        }

        void transform(MinecraftEntityGeometry.Key key, GeometryTransform transform, int mask) {
            pendingDrops.remove(key);
            Object extraction = telemetry.extraction(MinecraftTelemetry.GeometrySource.ENTITY_PLACEMENT, 1);
            geometry.transform(key, transform, mask);
            telemetry.published(extraction);
        }

        void drop(MinecraftEntityGeometry.Key key, Runnable acknowledgment) {
            pendingDrops.remove(key);
            geometry.drop(key);
            if (acknowledgment != null) acknowledgment.run();
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
    public void submitFrame(SceneOrigin origin, double cameraX, double cameraY, double cameraZ,
                            Matrix4f projection, Matrix4f viewRotation, long frameIndex) {
        MinecraftEntityGeometry current = geometry;
        if (current == null) return;
        beginFrame(current, origin, cameraX, cameraY, cameraZ,
                new Matrix4f(projection), new Matrix4f(viewRotation), frameIndex);
    }

    private void beginFrame(MinecraftEntityGeometry geometry, SceneOrigin origin,
                           double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation,
                           long frameIndex) {
        if (submittedPublication != null) {
            if (publicationPending(submittedPublication)) return;
            submittedPublication = null;
        }
        FrameBuild build = new FrameBuild(geometry, origin, frameIndex, instrumentation);
        int rbx = (int) origin.x();
        int rby = (int) origin.y();
        int rbz = (int) origin.z();
        if (!enabled()) {
            try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
                clearResidents(build);
                finishFrame(build);
                finishUpdateGroup(updates);
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
                finishFrame(build);
                finishUpdateGroup(updates);
            }
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        setCamera(camX, camY, camZ, projection, viewRotation);

        try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
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
            evictStaleAccels(build);
            evictStaleBes(build);
            finishFrame(build);
            finishUpdateGroup(updates);
        } catch (RuntimeException | Error t) {
            shutdown();
            throw t;
        }
    }

    private void finishUpdateGroup(MinecraftEntityGeometry.UpdateGroup updates) {
        GeometryPublication publication = updates.submit();
        submittedPublication = publicationPending(publication) ? publication : null;
    }

    static boolean publicationPending(GeometryPublication publication) {
        return publication != null && !publication.isVisible();
    }

    private void finishFrame(FrameBuild build) {
        List<MinecraftEntityGeometry.Key> drops = List.copyOf(pendingDrops);
        pendingDrops.clear();
        for (MinecraftEntityGeometry.Key key : drops) {
            if (key.domain() == ENTITY_GEOMETRY) {
                submitEntityRemoval(build, key, null);
            } else {
                build.drop(key, null);
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
        capture.currentMaterial = new MinecraftEntityMesh.Material(MinecraftMaterialIds.PARTICLE_BILLBOARD,
                null, MinecraftEntityMesh.Program.MATERIAL);
        capture.currentCoverage = MinecraftEntityMesh.Coverage.CUTOUT;
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
                        textures.contributeAtlas(layer.textureAtlasLocation());
                        capture.currentMaterial = new MinecraftEntityMesh.Material(
                                MinecraftMaterialIds.PARTICLE_BILLBOARD,
                                MinecraftEntityMesh.Texture.atlas(ResourceId.of(
                                        layer.textureAtlasLocation().getNamespace(), layer.textureAtlasLocation().getPath())),
                                MinecraftEntityMesh.Program.MATERIAL);
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
        MinecraftEntityGeometry.Key particleKey = key(PARTICLE_GEOMETRY, PARTICLE_KEY);
        if (capture.isEmpty()) {
            build.drop(particleKey, null);
        } else {
            MeshFingerprint fingerprint = meshFingerprint(capture);
            build.put(particleKey, capture.entityMesh(fingerprint.topologyRevision()),
                    transform(IDENTITY, build.origin), revision(fingerprint), PARTICLE_MASK, null);
        }
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
     * Every frame the BE is re-meshed (cheap) and its mesh hashed; the owner replaces its resident only when
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
        MeshFingerprint fingerprint = meshFingerprint(capture);
        if (entry == null || entry.meshHash != fingerprint.contentHash()) {
            // Geometry changed (or new BE) → rebuild, but only within this frame's budget. Over budget: keep
            // showing the previous geometry; a brand-new BE simply pops in over the next frames.
            if (beBuildsThisFrame >= beBuildsPerFrame()) {
                if (entry != null) {
                    recordVisibleBlockEntity(build);
                }
                build.telemetry.count("blockEntityGeometryDeferred", 1);
                return;
            }
            BeEntry rebuilt = buildBe(build, entry, be, fingerprint);
            rebuilt.lastSeen = now;
            beCache.put(key, rebuilt);
            recordVisibleBlockEntity(build);
            return;
        }
        recordVisibleBlockEntity(build);
    }

    /** Submits a keyed placement; an unchanged captured revision reuses that resident's retained mesh. */
    private BeEntry buildBe(FrameBuild build, BeEntry entry, BlockEntity be, MeshFingerprint fingerprint) {
        BlockPos p = be.getBlockPos();
        beBuildsThisFrame++;

        BeEntry e = entry != null ? entry : new BeEntry();
        e.bx = p.getX();
        e.by = p.getY();
        e.bz = p.getZ();
        e.meshHash = fingerprint.contentHash();
        long key = p.asLong();
        MinecraftEntityGeometry.Key geometryKey = key(BLOCK_ENTITY_GEOMETRY, key);
        build.put(geometryKey, capture.entityMesh(fingerprint.topologyRevision()),
                GeometryTransform.translation(p.getX(), p.getY(), p.getZ()), revision(fingerprint), MASK_ALL, null);
        build.telemetry.count("blockEntityGeometrySubmissions", 1);
        return e;
    }

    record MeshFingerprint(long contentHash, long topologyRevision) { }

    private MinecraftEntityGeometry.MeshRevision revision(MeshFingerprint fingerprint) {
        return new MinecraftEntityGeometry.MeshRevision(
                meshRevisionEpoch, fingerprint.contentHash(), fingerprint.topologyRevision());
    }

    /** Computes content and topology fingerprints together during the capture's required change scan. */
    static MeshFingerprint meshFingerprint(RtEntityCapture capture) {
        long content = 1469598103934665603L;
        long topology = 1469598103934665603L;
        float[] v = capture.verts.elements();
        int vn = capture.verts.size();
        for (int i = 0; i < vn; i++) {
            content = (content ^ (Float.floatToRawIntBits(v[i]) & 0xffffffffL)) * 1099511628211L;
        }
        topology = (topology ^ (vn / 3)) * 1099511628211L;
        int[] x = capture.idx.elements();
        int xn = capture.idx.size();
        for (int i = 0; i < xn; i++) {
            long value = x[i] & 0xffffffffL;
            content = (content ^ value) * 1099511628211L;
            topology = (topology ^ value) * 1099511628211L;
        }
        float[] uv = capture.uvList.elements();
        int uvn = capture.uvList.size();
        for (int i = 0; i < uvn; i++) {
            long value = Float.floatToRawIntBits(uv[i]) & 0xffffffffL;
            content = (content ^ value) * 1099511628211L;
        }
        topology = (topology ^ uvn) * 1099511628211L;
        for (MinecraftEntityMesh.Triangle surface : capture.surfaces) {
            long value = surface.hashCode() & 0xffffffffL;
            content = (content ^ value) * 1099511628211L;
        }
        return new MeshFingerprint(content, topology);
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
            MinecraftEntityGeometry.Key geometryKey = key(BLOCK_ENTITY_GEOMETRY, key);
            build.drop(geometryKey, null);
            it.remove();
        }
    }

    private static float[] translationTransform(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    private static MinecraftEntityGeometry.Key key(long domain, long value) {
        return new MinecraftEntityGeometry.Key(domain, value);
    }

    private static MinecraftTelemetry.GeometrySource sourceKind(MinecraftEntityGeometry.Key key) {
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
        MinecraftEntityGeometry.Key key = key(ENTITY_GEOMETRY, Integer.toUnsignedLong(entityId));
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
        MeshFingerprint fingerprint = meshFingerprint(capture);
        long capturedMeshHash = fingerprint.contentHash();
        if (state.beginInitialSubmission(capturedMeshHash)) {
            build.telemetry.count("entityPlacementInitialSubmissions", 1);
            EntityState submitted = state;
            if (build.telemetry.enabled()) {
                long version = state.profileMeshSubmission();
                long sourceFrame = build.telemetry.frameSerial();
                long visibilityToken = state.meshVisibilityToken;
                MinecraftTelemetry.Instrumentation telemetry = build.telemetry;
                build.put(key, capture.entityMesh(fingerprint.topologyRevision()), transform,
                        revision(fingerprint), mask,
                        () -> submitted.meshPublicationAccepted(version, sourceFrame, visibilityToken, telemetry));
            } else {
                build.put(key, capture.entityMesh(fingerprint.topologyRevision()), transform,
                        revision(fingerprint), mask, null);
            }
        } else {
            build.telemetry.count("entityPlacementFreshnessEligible", 1);
            if (state.requiresPut(capturedMeshHash)) {
                state.meshSubmitted(capturedMeshHash);
                build.telemetry.count("entityMeshOnlyUpdates", 1);
                if (build.telemetry.enabled()) {
                    long version = state.profileMeshSubmission();
                    long sourceFrame = build.telemetry.frameSerial();
                    long visibilityToken = state.meshVisibilityToken;
                    EntityState submitted = state;
                    MinecraftTelemetry.Instrumentation telemetry = build.telemetry;
                    build.put(key, capture.entityMesh(fingerprint.topologyRevision()), transform,
                            revision(fingerprint), mask,
                            () -> submitted.meshPublicationAccepted(version, sourceFrame, visibilityToken, telemetry));
                } else {
                    build.put(key, capture.entityMesh(fingerprint.topologyRevision()), transform,
                            revision(fingerprint), mask, null);
                }
            } else {
                build.transform(key, transform, mask);
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
            MinecraftEntityGeometry.Key key = key(ENTITY_GEOMETRY, Integer.toUnsignedLong(id));
            submitEntityRemoval(build, key, entry.getValue());
            it.remove();
        }
    }

    private void submitEntityRemoval(FrameBuild build, MinecraftEntityGeometry.Key key, EntityState retiring) {
        build.drop(key, retiring != null ? retiring::invalidateMeshVisibility : null);
    }

    /** Remove every retained resident when this capture path is disabled. */
    private void clearResidents(FrameBuild build) {
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
        for (int id : entityStates.keySet()) {
            MinecraftEntityGeometry.Key key = key(ENTITY_GEOMETRY, Integer.toUnsignedLong(id));
            submitEntityRemoval(build, key, null);
        }
        for (long value : beCache.keySet()) {
            MinecraftEntityGeometry.Key key = key(BLOCK_ENTITY_GEOMETRY, value);
            build.drop(key, null);
        }
        MinecraftEntityGeometry.Key particleKey = key(PARTICLE_GEOMETRY, PARTICLE_KEY);
        build.drop(particleKey, null);
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
        meshRevisionEpoch++;
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
        for (int id : entityStates.keySet()) pendingDrops.add(key(ENTITY_GEOMETRY, Integer.toUnsignedLong(id)));
        for (long value : beCache.keySet()) pendingDrops.add(key(BLOCK_ENTITY_GEOMETRY, value));
        pendingDrops.add(key(PARTICLE_GEOMETRY, PARTICLE_KEY));
        entityStates.clear();
        beCache.clear();
        collector.clearCaches();
    }

    /** Prevent deferred profiling callbacks from outliving this contribution's retained-scene ownership. */
    public void onSourceStopped() {
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
    }

    /** Clears CPU capture state before entity IDs can be reused by a new world. */
    public void resetWorldState() {
        meshRevisionEpoch++;
        entityStates.values().forEach(EntityState::invalidateMeshVisibility);
        entityStates.clear();
        beCache.clear();
        pendingDrops.clear();
        submittedPublication = null;
        collector.clearCaches();
    }

    /** Clear capture state; the bound geometry contribution tears down all GPU residents. */
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
        submittedPublication = null;
        collector.clearCaches();
    }
}
