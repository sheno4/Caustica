package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.light.RtLightScene;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryMaterialResolution;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtJitter;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pass.RenderPassManager;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.backend.GraphicsSubmission;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtToneLut;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import dev.comfyfluffy.caustica.api.provider.SceneCamera;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the active retained scene with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until a scene is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
final class RtFrameRenderer {
    static final RtFrameRenderer INSTANCE = new RtFrameRenderer();

    public static boolean enabled() {
        return RtRuntime.frameActive();
    }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex avoid unnecessary global-memory dereferences; WorldPushConstantsData is
    // generated from the same Slang module and owns this second ABI as well. debugView is no longer
    // part of it -- no world shader reads it anymore; debug views are a downstream compute pass.
    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return CausticaConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static int packHalf2(float x, float y) {
        return (Float.floatToFloat16(y) << 16) | (Float.floatToFloat16(x) & 0xffff);
    }

    // Modulus of the world-pinned procedural domain anchor. Documented engine constant, not a per-surface
    // tunable: a very low-frequency field could alias across it where the wave spectrum does not.
    private static final int PROCEDURAL_ANCHOR_MASK = 4095;
    // Renderer look metadata is exposure/LMT only; scene providers own their photometric calibration.
    private static final RtLookPackage LOOK = RtLookPackage.current();
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    // Monotonic per-composite frame counter used for cache eviction, shader sampling, and diagnostics.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private final RtWorldResources worldResources = new RtWorldResources();
    // World push data lives in a host-visible BDA ring; only the slot address and a small hot subset are
    // pushed inline (the full generated structure exceeds NVIDIA's 256-byte push-constant ceiling).
    // Exact graphics completion guards host writes; ring depth only avoids routine waits.
    private static final int PUSH_RING = 6;
    private PushSlot[] pushRing;
    private int pushSlot;
    private final RtLightScene lightScene = new RtLightScene();
    private final RtSceneGeometryManager sceneGeometry = new RtSceneGeometryManager(
            (material, coverage) -> RtGeometryMaterialResolution.resolve(
                    material, coverage, worldResources.materialEpoch.geometryBindings()));
    private final RtFrameResources frameResources = new RtFrameResources();
    private RenderPassManager renderPassManager;
    private long renderPassSceneId = Long.MIN_VALUE;

    private static final class PushSlot {
        final GpuBuffer buffer;
        final RtGpuExecutor.TrackedGraphicsUse graphicsUse = new RtGpuExecutor.TrackedGraphicsUse();

        PushSlot(GpuBuffer buffer) {
            this.buffer = buffer;
        }
    }
    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private float previousProceduralTime;
    private boolean proceduralTimeValid;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from the host adapter (unjittered projection, rotation, and position).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private FrameSnapshot frameSnapshot;

    // This frame's TLAS handle, published after prepareTlas so a world-overlay pass's rayQueryEXT
    // occlusion test can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (the transient overlay buffer runs later on the same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private RtGpuExecutor.GraphicsUse pendingGraphicsUse;

    private RtFrameRenderer() {
        worldResources.attachSceneGeometry(sceneGeometry);
    }

    /** This frame's TLAS handle (0 if none built yet), for host overlay occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    /** Renderer-owned geometry manager shared by every active scene producer. */
    public RtSceneGeometryManager sceneGeometry() {
        return sceneGeometry;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /** Read-only access to the auto-exposure controller, for diagnostics (F3 entry, frame stats log). */
    public RtExposure exposure() {
        return frameResources.exposure;
    }

    /**
     * Export the latest RT scene image at the exact input seam of the Look/LMT stage.
     *
     * <p>The GPU image stores {@code sceneLinear * preExposure} in fp16. This readback multiplies RGB by
     * the display shader's current 1x1 {@code residualExposure}, in float32, then quantizes the resulting
     * exposure-adjusted scene-linear image to fp16 EXR. Metadata keeps both factors so the original scene-linear
     * values can be reconstructed with {@code RGB / (preExposure * residualExposure)}.
     *
     * @return {@code true} when a current RT frame was available and written
     */
    public boolean exportLatestResidualExposureExr(Path outputPath) throws java.io.IOException {
        GpuContext context = GpuContext.currentOrNull();
        if (context != null) {
            context.backend().assertRenderThread();
        }
        GpuContext ctx = GpuContext.currentOrNull();
        if (!enabled() || failed || ctx == null || frameResources.rrOutput == null || frameResources.exposure.image() == null
                || frameResources.displayW <= 0 || frameResources.displayH <= 0 || pendingGraphicsUse != null) {
            return false;
        }

        long pixelCount = Math.multiplyExact((long) frameResources.displayW, (long) frameResources.displayH);
        long rgbaBytes = Math.multiplyExact(pixelCount, 4L * Short.BYTES);
        long totalBytes = Math.addExact(rgbaBytes, Float.BYTES);
        if (pixelCount > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("EXR capture is too large for a Java array: "
                    + frameResources.displayW + "x" + frameResources.displayH);
        }

        // All ordinary frame commands have been submitted before the F2 key is handled. Drain them before
        // a private one-shot copy so rrOutput and the exposure image describe the same completed frame.
        ctx.waitIdle();
        GpuBuffer readback = ctx.createReadbackBuffer(totalBytes, "residual-exposure EXR readback");
        try {
            ctx.submitSync(cmd -> recordExrReadback(ctx, cmd, readback, rgbaBytes));
            readback.invalidate();

            float residualExposure = MemoryUtil.memGetFloat(readback.mapped + rgbaBytes);
            RtExposure.CaptureMetadata exposureMetadata = frameResources.exposure.captureMetadata(residualExposure);
            short[] exposedRgba = new short[Math.toIntExact(pixelCount * 4L)];
            for (int sample = 0; sample < exposedRgba.length; sample++) {
                short storedHalf = MemoryUtil.memGetShort(readback.mapped + (long) sample * Short.BYTES);
                float value = Float.float16ToFloat(storedHalf);
                if ((sample & 3) != 3) {
                    value *= residualExposure;
                }
                // Residual exposure is expected to keep this seam comfortably centred in fp16. Clamp only
                // true outliers/infinities so a pathological light cannot poison a grading application.
                value = Math.clamp(value, -65504.0f, 65504.0f);
                exposedRgba[sample] = Float.floatToFloat16(value);
            }

            RtOpenExrWriter.write(outputPath, frameResources.displayW, frameResources.displayH, exposedRgba,
                    new RtOpenExrWriter.Metadata(
                            exposureMetadata.preExposure(),
                            exposureMetadata.residualExposure(),
                            exposureMetadata.absoluteExposure(),
                            exposureMetadata.mode(),
                            exposureMetadata.evScene(),
                            exposureMetadata.evTarget(),
                            exposureMetadata.evApplied(),
                            LOOK.id() + "@" + LOOK.packageVersion(),
                            frameCounter));
            return true;
        } finally {
            readback.destroy();
        }
    }

    private void recordExrReadback(GpuContext ctx, VkCommandBuffer cmd, GpuBuffer readback, long exposureOffset) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     "residual-exposure EXR readback")) {
            VkImageMemoryBarrier.Buffer imageBarriers = VkImageMemoryBarrier.calloc(2, stack);
            imageBarriers.get(0).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(frameResources.rrOutput.image);
            imageBarriers.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            imageBarriers.get(1).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(frameResources.exposure.image().image);
            imageBarriers.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarriers);

            VkBufferImageCopy.Buffer sceneCopy = VkBufferImageCopy.calloc(1, stack);
            sceneCopy.get(0).bufferOffset(0L);
            sceneCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            sceneCopy.get(0).imageExtent().set(frameResources.displayW, frameResources.displayH, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, frameResources.rrOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    readback.handle, sceneCopy);

            VkBufferImageCopy.Buffer exposureCopy = VkBufferImageCopy.calloc(1, stack);
            exposureCopy.get(0).bufferOffset(exposureOffset);
            exposureCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            exposureCopy.get(0).imageExtent().set(1, 1, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, frameResources.exposure.image().image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    readback.handle, exposureCopy);

            VkMemoryBarrier.Buffer hostBarrier = VkMemoryBarrier.calloc(1, stack);
            hostBarrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, hostBarrier, null, null);
        }
    }

    /**
     * Whether the current frame must retain source rasterization while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate or observe
     * the newly uploaded atlas. This prevents the source renderer from being suppressed before a deliberately
     * transient {@link #composite} return. Such a return is not a renderer failure and must not trip the host's
     * permanent safety latch.</p>
     */
    public boolean requiresSourceWorldFallback() {
        // Pipeline creation publishes a new material epoch and deliberately makes composite() return
        // false once so the scene source can apply the matching full clear. Keep source rasterization alive for that
        // bring-up frame; otherwise it is suppressed before composite() discovers it must fall back and the
        // host permanently latches the resulting missing replacement frame.
        return worldResources.requiresSourceFallback(ProviderManager.INSTANCE.bindlessTextureCapacity());
    }

    /**
     * Complete the material epoch while startup is retaining source presentation. The runtime calls this
     * only after the scene update has applied the pending full clear, so activation depends on resource
     * readiness rather than an additional tick or rendered frame.
     */
    public boolean completeStartupBoundary() {
        return worldResources.completeStartupBoundary(ProviderManager.INSTANCE.bindlessTextureCapacity());
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on the source renderer until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
    }

    /** Capture the immutable host frame for the next composite. Called from the host render adapter. */
    public void captureFrame(FrameSnapshot snapshot) {
        frameSnapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** Reset exposure filtering after an explicit render-state invalidation such as F3+A. */
    public void resetExposureHistory() {
        frameResources.exposure.requestReset();
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — host overlay raster passes reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Invalidate the published presentation snapshot at the start of host rendering. Menu and loading
     * frames do not call {@link #composite()}, so retaining the previous world snapshot would present stale
     * HDR content instead of selecting the SDR-to-PQ path.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        RtFramePresenter.INSTANCE.beginFrame();
        frameSnapshot = null;
    }

    /** This frame's completion token, valid until {@link #finishGraphicsUse()} signals it. */
    public RtGpuExecutor.GraphicsUse currentGraphicsUse() {
        GpuContext context = GpuContext.currentOrNull();
        if (context != null) {
            context.backend().assertRenderThread();
        }
        return pendingGraphicsUse;
    }

    /**
     * Record every registered {@link RenderStage#OVERLAY} pass on its own transient command buffer and
     * submit it. Called once per frame from the host's post-upscale hook — this can't run inside the main
     * {@link #composite} recording because the
     * world hasn't been upscaled yet at that point. No-op if RT hasn't run this frame ({@link #composite}
     * never reached {@link #ensureRenderPassManager}).
     */
    public void recordOverlayPasses() {
        if (renderPassManager == null) {
            return;
        }
        // A pass throwing is already isolated by RenderPassManager (disables that pass, logs, moves on);
        // this only guards the alloc/submit plumbing around it, so a transient device-lost-adjacent failure
        // here can't interrupt the rest of the frame either.
        try {
            GpuContext context = GpuContext.currentOrNull();
            if (context == null) {
                return;
            }
            GraphicsSubmission submission = context.backend().createGraphicsSubmission();
            VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
            renderPassManager.record(RenderStage.OVERLAY, cmd);
            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(overlay passes) failed");
            }
            submission.execute(cmd);
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Recording overlay render passes failed", t);
        }
    }

    /** Signal this RT frame's shared completion token after its final TLAS consumer (world overlay). */
    public void finishGraphicsUse() {
        RtGpuExecutor.GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before graphics use completed");
        }
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        ctx.gpuExecutor().endGraphicsUse(submission, graphicsUse);
        pendingGraphicsUse = null;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        frameCounter++; // global frame serial used by per-frame source rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        if (failed) {
            return false;
        }
        GpuContext ctx = GpuContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Providers prepare their frame contributions before the ready gate below. A failing provider is
        // disabled and cleaned up independently, so another provider can continue serving the frame.
        ProviderManager.INSTANCE.prepareFrame();
        FrameSnapshot snapshot = frameSnapshot;
        ProviderManager.PrimaryScene primaryScene = ProviderManager.INSTANCE.primaryScene();
        if (primaryScene == null || snapshot == null) {
            // No scene was captured this frame. Skip RT so the present path falls back to the host image.
            return false;
        }
        try {
            if (!ensurePresentationResources(ctx, snapshot.sceneId(), width, height)) {
                return false;
            }
            RtPipeline active = ensureWorld(ctx);
            if (active == null) {
                return false;
            }
            if (worldResources.traceGate) {
                worldResources.traceGate = false;
                return false;
            }
            updateMotion(snapshot);
            recordFrame(ctx, active, nativeColorImage, snapshot, primaryScene);
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active: {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (ProviderManager.SceneSourceUnavailableException unavailable) {
            return false;
        } catch (Throwable t) {
            RtFramePresenter.INSTANCE.invalidateRenderedFrame();
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to source rasterization", t);
            return false;
        }
    }

    /** Build every display-sized resource while startup is still presenting the source renderer. */
    public boolean ensurePresentationResourcesReady(GpuContext ctx, long sceneId, int width, int height) {
        if (failed || sceneId == 0L) {
            return false;
        }
        try {
            return ensurePresentationResources(ctx, sceneId, width, height);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT presentation resource bring-up failed; reverting to host presentation", t);
            return false;
        }
    }

    private boolean ensurePresentationResources(GpuContext ctx, long sceneId, int width, int height)
            throws IOException {
        if (renderPassSceneId != sceneId) {
            renderPassSceneId = sceneId;
            if (renderPassManager != null) {
                renderPassManager.onWorldChanged();
            }
        }
        ensureRenderPassManager(ctx);
        frameResources.ensurePresentationPipelines(ctx, LOOK);
        if (worldResources.materialEpoch.waitingForReplacementAtlas()) return false;
        if (frameResources.ensureSized(ctx, width, height, renderPassManager, worldResources.pipeline)) {
            mvHasPrev = false;
            proceduralTimeValid = false;
        }
        refreshPipelineShapeIfNeeded(ctx);
        return true;
    }

    /**
     * Bring the world pipeline and authored texture atlases up before scene tessellation so the immutable
     * material snapshot is available to the first worker build. Driven ahead of scene-source update. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public boolean ensureResourcesReady(GpuContext ctx, SceneResources sceneResources) {
        worldResources.materialEpoch.observeBaseColorAtlas(sceneResources.baseColorAtlasView());
        if (failed || worldResources.materialEpoch.reloadPending()) {
            return false;
        }
        if (worldResources.pipeline != null) {
            return true;
        }
        if (!sceneResources.sceneReady() || !worldResources.materialEpoch.atlasReady()) {
            return false;
        }
        try {
            return ensureWorld(ctx) != null;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to source rasterization", t);
            return false;
        }
    }

    private RtPipeline ensureWorld(GpuContext ctx) throws IOException {
        ensureRenderPassManager(ctx);
        ensurePushRing(ctx);
        return worldResources.ensureWorld(ctx, renderPassManager, frameResources);
    }

    private void ensurePushRing(GpuContext ctx) {
        if (pushRing != null) {
            return;
        }
        pushRing = new PushSlot[PUSH_RING];
        for (int i = 0; i < PUSH_RING; i++) {
            pushRing[i] = new PushSlot(ctx.createBuffer(WORLD_PUSH_SIZE,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i));
        }
    }
    private void ensureRenderPassManager(GpuContext ctx) throws IOException {
        if (renderPassManager == null) {
            renderPassManager = RenderPassManager.create(ctx, RtRuntime.INSTANCE.runtimeContributions(),
                    CausticaApi.options());
        }
    }

    private void refreshPipelineShapeIfNeeded(GpuContext ctx) {
        worldResources.refreshShape(ctx, renderPassManager, frameResources);
    }

    private void refreshPassResourcesIfNeeded(GpuContext ctx) {
        worldResources.refreshPassResources(ctx, renderPassManager);
    }
    /**
     * Called before the host re-stitches its atlas and reloads source textures. The host frees old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every bindless descriptor reference — so the host can free
     * its textures cleanly. The pipeline is cheap to rebuild (no scene
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). The new material epoch clears retained geometry before trace.
     */
    public void onResourceReloadStart() {
        worldResources.beginReload(GpuContext.currentOrNull(), renderPassManager);
    }

    /**
     * Resume resource convergence after the host rejected a reload before replacing its pack-owned images.
     * The pre-reload phase has already detached the old descriptors, so the next world bring-up rebuilds
     * them against the still-current host resources.
     */
    public void onResourceReloadFailed() {
        worldResources.resourceReloadFailed();
    }

    /** Notify runtime-activation-scoped passes after the host has published the replacement pack epoch. */
    public void onResourcePackApplied() {
        worldResources.resourcePackApplied(renderPassManager);
    }

    /** Reset world-scoped pass state when the active render session changes worlds. */
    public void onWorldChanged() {
        resetExposureHistory();
        if (renderPassManager != null) {
            renderPassManager.onWorldChanged();
        }
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion(FrameSnapshot snapshot) {
        snapshot.copyProjectionTo(frameProjection);
        snapshot.copyViewRotationTo(frameViewRotation);
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (snapshot.cameraX() - mvPrevCamX);
            mvCamDeltaY = (float) (snapshot.cameraY() - mvPrevCamY);
            mvCamDeltaZ = (float) (snapshot.cameraZ() - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = snapshot.cameraX();
        mvPrevCamY = snapshot.cameraY();
        mvPrevCamZ = snapshot.cameraZ();
        mvHasPrev = true;
    }

    private void recordFrame(GpuContext ctx, RtPipeline active, long nativeColorImage,
                             FrameSnapshot snapshot, ProviderManager.PrimaryScene primaryScene) {
        long dstImage = nativeColorImage;
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        // Reserve the graphics-use value that guards this frame's reusable TLAS and source resources.
        RtGpuExecutor.GraphicsUse graphicsUse = gpuExecutor.beginGraphicsUse(submission);
        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        // Reuse a completed readback slot, then latch one pre-exposure value for both raygen and resolve.
        // This belongs after the timeline snapshot and before any world push data is written.
        frameResources.exposure.beginFrame(graphicsUseWaiter);
        pendingGraphicsUse = graphicsUse;
        RtSceneGeometryManager.FrameUpdate dynamicGeometry = null;
        RtLightScene.Frame frameLights = null;
        PushSlot framePushSlot = null;
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        int debugView = debugView();
        RtSceneSource.Retained retained = primaryScene.retained();
        SceneOrigin sceneOrigin = retained.origin();
        RtSceneSource.RetainedLights retainedLights = retained.retainedLights();
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // A debug view observes this ordinary path; it never changes jitter or disables RR.
            boolean rrPath = RtDlssRr.enabled();
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                RtJitter.INSTANCE.prepare(frameResources.renderW, frameResources.renderH, frameResources.displayW);
                jitterX = RtJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = RtJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including geometry addresses and damage entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            PushSlot selectedPushSlot = pushRing[pushSlot];
            graphicsUseWaiter.await(selectedPushSlot.graphicsUse);
            framePushSlot = selectedPushSlot;
            GpuBuffer pushBuf = selectedPushSlot.buffer;
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            int flags = snapshot.proceduralSurfaceAnimationEnabled() ? 0b10000 : 0;
            int cameraMediumIorTransmission = 0;
            Float3 cameraMedium = new Float3(0.0f, 0.0f, 0.0f);
            FrameSnapshot.CameraMedium frameMedium = snapshot.cameraMedium();
            if (frameMedium != null) {
                var materialSnapshot = worldResources.materialEpoch.snapshot();
                int materialId = materialSnapshot.bindingId(frameMedium.material().id());
                var material = materialSnapshot.material(materialId);
                flags |= 0b01 | (material.surfaceImplementation() << 8);
                cameraMediumIorTransmission = packHalf2(material.specularIor(), material.transmissionWeight());
                FrameSnapshot.LinearRgb sourceColor = frameMedium.sourceColor();
                cameraMedium = new Float3(sourceColor.red(), sourceColor.green(), sourceColor.blue());
            }
            float time = (float) (snapshot.timeSeconds() % 3600.0);
            float delta = time - previousProceduralTime;
            // A first frame, long pause, or one-hour phase wrap has no adjacent frame to reproject. Use
            // the current phase so a procedural surface reports no motion instead of a huge jump.
            float previousTime = proceduralTimeValid && delta >= 0f && delta <= 0.25f
                    ? previousProceduralTime : time;
            previousProceduralTime = time;
            proceduralTimeValid = true;
            // Procedural domain anchor: the scene rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so a
            // pattern stays fixed in the world as the player moves and the rebase origin shifts.
            frameLights = lightScene.prepareFrame(ctx,
                    ProviderManager.INSTANCE.frameLights(), sceneOrigin.x(), sceneOrigin.y(),
                    sceneOrigin.z(), snapshot.metersPerWorldUnit(), graphicsUseWaiter);
            double proceduralPeriod = PROCEDURAL_ANCHOR_MASK + 1.0;
            Float3 proceduralDomainOffset = new Float3(sceneOrigin.wrappedX(proceduralPeriod),
                    sceneOrigin.wrappedY(proceduralPeriod), sceneOrigin.wrappedZ(proceduralPeriod));

            // Providers and host sources submit only retained atomic updates. The manager publishes ready
            // groups and builds one source-neutral TLAS snapshot from that published scene.
            ProviderManager.INSTANCE.submitGeometry(ctx, sceneOrigin,
                    new SceneCamera(snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ(),
                            frameProjection.get(new float[16]), frameViewRotation.get(new float[16])));
            dynamicGeometry = sceneGeometry.beginUpdate(ctx, sceneOrigin);
            new WorldPushData(
                    frameInvViewProj,
                    new Float3(sceneOrigin.relativeX(snapshot.cameraX()),
                            sceneOrigin.relativeY(snapshot.cameraY()),
                            sceneOrigin.relativeZ(snapshot.cameraZ())),
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    flags,
                    maxBounces(),
                    cameraMedium,
                    cameraMediumIorTransmission,
                    time,
                    proceduralDomainOffset,
                    mvCurProjView,
                    new Float4(retainedLights.rebaseOffsetX(), retainedLights.rebaseOffsetY(),
                            retainedLights.rebaseOffsetZ(), retainedLights.metersPerWorldUnit()),
                    new Int4(retainedLights.rootNodeIndex(), retainedLights.finiteLightCount(),
                            retainedLights.linkedEmitterCount(), 0),
                    previousTime,
                    new Int4(frameLights.rootNodeIndex(), frameLights.finiteLightCount(),
                            frameLights.distantFirstLight(), frameLights.distantLightCount()),
                    frameLights.metersPerWorldUnit(),
                    CausticaConfig.Rt.Lights.RIS_CANDIDATES.value(),
                    // Must be the SAME value the exposure resolve divides out this frame (it reads it
                    // from the same RtExposure accessor), or the two stop cancelling.
                    frameResources.exposure.preExposure()
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            // Upload source textures registered this frame before the trace, preserving descriptor order.
            worldResources.materialEpoch.uploadPendingTextures(ctx, active);
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = sceneGeometry.prepareTlas(ctx, dynamicGeometry, graphicsUse);
            }
            active.setTlas(frameTlas.accel.handle, graphicsUse, graphicsUseWaiter);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Push the BDA ring slot's address plus the small hot subset used directly by the shaders.
            // Every 64-bit device address the trace needs lives here, not behind worldPushAddr: the
            // geometry/material tables are read from world.rahit/world.rchit, which never load
            // WorldPush at all, and the RIS light buffers are read from world.rgen's hot inner loop, so
            // none of them should cost an extra BDA dereference to find.
            ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
            new WorldPushConstantsData(pushBuf.deviceAddress, sceneGeometry.geometryTableAddress(dynamicGeometry),
                    sceneGeometry.instanceHistoryAddress(dynamicGeometry),
                    worldResources.materialEpoch.bindingTableAddress(),
                    worldResources.materialEpoch.surfaceTableAddress(),
                    retainedLights.lightAddress(), retainedLights.nodeAddress(),
                    frameLights.lightAddress(), frameLights.nodeAddress(),
                    frameResources.continuationQueue.deviceAddress,
                    (int) frameCounter).write(pushConstants);
            renderPassManager.beginFrame();
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.skyLut")) {
                renderPassManager.record(RenderStage.ENVIRONMENT_PREPARE, cmd);
            }
            renderPassManager.record(RenderStage.BEFORE_TRACE, cmd);
            // PassFrame may publish a host-owned resource while recording either pre-trace stage. Resolve
            // those publications before this command buffer first binds set 2, including on the first
            // frame where a host atlas becomes available.
            refreshPassResourcesIfNeeded(ctx);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.tracePrimary")) {
                active.trace(cmd, frameResources.renderW, frameResources.renderH, pushConstants, 0);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // continuation/guide writes visible to the indirect trace
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.traceIndirect")) {
                active.trace(cmd, frameResources.renderW, frameResources.renderH, pushConstants, 1);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), frameResources.renderW, frameResources.renderH, frameResources.displayW, frameResources.displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), frameResources.output, frameResources.gDepth, frameResources.gMotion, frameResources.gAlbedo,
                            frameResources.gSpecAlbedo, frameResources.gNormal, frameResources.gSpecMotion, frameResources.rrOutput, frameResources.renderW, frameResources.renderH, frameResources.displayW, frameResources.displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            }

            // When DLSS-RR did not produce the display-res image (disabled or a runtime failure), bring
            // the render-res trace up to display res with a linear blit so the display mapper and
            // downstream debug pass always have a valid display-res scene image. With RR off
            // render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanBarriers.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, frameResources.output, frameResources.rrOutput);
                }
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // reconstructed output visible to exposure histogram

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                frameResources.exposure.record(ctx, cmd, stack, frameResources.rrOutput, frameResources.gDepth, frameResources.gAlbedo);
                frameResources.exposure.recordStateReadback(cmd, stack);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // exposure image visible to downstream passes

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "post chain");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.postChain")) {
                renderPassManager.record(RenderStage.AFTER_RECONSTRUCTION, cmd);
            }
            renderPassManager.record(RenderStage.LOOK, cmd);

            // Only now is it known which image the post chain left the scene in: participation is decided
            // inside each pass's record(). Rebinding here is a no-op unless the set of chained passes
            // changed, and it precedes the descriptor's own bind inside dispatch().
            RtToneLut displayLookLut = frameResources.lookLut;
            frameResources.displayPipeline.setImages(frameResources.displayImage.view, renderPassManager.sceneColor().view,
                    frameResources.exposure.image().view, frameResources.hdrDisplayImage.view,
                    frameResources.sdrToneLut.view(), frameResources.sdrToneLut.sampler(), frameResources.hdrToneLut.view(), frameResources.hdrToneLut.sampler(),
                    displayLookLut.view(), displayLookLut.sampler(), graphicsUse, graphicsUseWaiter);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                frameResources.displayPipeline.dispatch(cmd, frameResources.displayW, frameResources.displayH, CausticaConfig.Rt.Hdr.enabled(),
                        frameResources.sdrToneLut.size, CausticaConfig.Rt.Tonemap.GAMMA.value(), frameResources.loadedHdrLutNits,
                        true, frameResources.lookLut.size);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // display output visible to debug composite

            if (debugView != 0) {
                // Debug content is composited only after the real scene has completed trace, RR/fallback,
                // exposure, and display mapping. It therefore observes the renderer without perturbing
                // exposure history or feeding literal diagnostic colors through ACES. Debug presentation
                // remains SDR for now; a PQ swapchain uses the existing SDR->PQ conversion path.
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "debug present");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.debugPresent")) {
                    frameResources.debugPresentPipeline.dispatch(cmd, frameResources.displayW, frameResources.displayH, debugView,
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_SIGMA.value(),
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_FLOOR.value());
                }
            }
            VulkanBarriers.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, frameResources.displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, frameResources.displayW, frameResources.displayH));
            }
            VulkanBarriers.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        submission.execute(cmd);
        RtFramePresenter.INSTANCE.publish(new RtFramePresenter.RenderedFrame(
                frameResources.hdrDisplayImage, frameResources.gMotion, frameResources.gDepth,
                frameResources.renderW, frameResources.renderH, mvCurProjView, mvPushMatrix,
                CausticaConfig.Rt.Hdr.enabled() && debugView == 0));
        // Do not attach a merely reserved token: failed recording may never signal it. Once execute succeeds,
        // every owner in this frame's manifest is protected through the final overlay consumer.
        framePushSlot.graphicsUse.mark(graphicsUse);
        sceneGeometry.markGraphicsUse(dynamicGeometry, graphicsUse);
        lightScene.markGraphicsUse(frameLights, graphicsUse);
        frameResources.exposure.markStateReadbackUse(graphicsUse);
    }

    public void destroy() {
        // Session teardown stops the GPU executor and waits the device idle before entering here, so the
        // TLAS ring's slots are no longer in flight and can be freed immediately.
        sceneGeometry.shutdown();
        lightScene.destroy();
        RtDlssRr.INSTANCE.destroy();
        RtFramePresenter.INSTANCE.destroyGpuResources();
        if (renderPassManager != null) {
            renderPassManager.destroy();
            renderPassManager = null;
        }
        renderPassSceneId = Long.MIN_VALUE;
        worldResources.destroy();
        frameResources.destroy();
        if (pushRing != null) {
            for (PushSlot slot : pushRing) {
                if (slot != null) {
                    slot.buffer.destroy();
                }
            }
            pushRing = null;
        }
        mvHasPrev = false;
        proceduralTimeValid = false;
        failed = false;
        loggedActive = false;
        frameSnapshot = null;
        currentTlasHandle = 0L;
        pendingGraphicsUse = null;
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, GpuImage src, GpuImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1);
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

}
