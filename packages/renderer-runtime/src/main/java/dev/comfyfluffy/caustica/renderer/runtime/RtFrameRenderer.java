package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackend;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserCommonSettings;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserImage;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserInputs;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;
import dev.comfyfluffy.caustica.renderer.presentation.RtNrdComposePipeline;
import dev.comfyfluffy.caustica.renderer.presentation.gen.NrdPlaneFrameData;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;

import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.session.EngineSessionServices;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkBlitImageInfo2;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyImageInfo2;
import org.lwjgl.vulkan.VkCopyImageToBufferInfo2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit2;
import org.lwjgl.vulkan.VkImageCopy2;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.renderer.runtime.pipeline.RtJitter;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.RtLookPackage;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationResources;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceImages;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.renderer.runtime.pass.RtPassSchedulerBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneLut;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * On-screen composite. Each frame, ray-trace into scene color and guide images, route them through
 * raw presentation, NRD temporal denoising, or DLSS Ray Reconstruction, then copy the display-mapped
 * result back to the world target at the end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>NRD and DLSS-RR use per-frame sub-pixel camera jitter. Ray Reconstruction or the configured
 * neutral temporal upscaler may select a lower render extent and reconstruct to display resolution.
 * A failed reconstruction evaluation falls back to a linear upscale of the noisy trace.
 *
 * <p>Traces the active retained scene with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until a scene is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtFrameRenderer {
    static final float NRD_DENOISING_RANGE = 50_000.0f;
    private static final Logger LOGGER = LoggerFactory.getLogger(RtFrameRenderer.class);
    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // The reflected world binding root is assembled separately from this larger addressable frame block.
    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static RtExposure.Settings exposureSettings() {
        return new RtExposure.Settings(
                CausticaConfig.Rt.Exposure.MODE.get(),
                CausticaConfig.Rt.Exposure.MANUAL_EV.value(),
                CausticaConfig.Rt.Exposure.KEY.value(),
                CausticaConfig.Rt.Exposure.ADAPT_DARKEN.value(),
                CausticaConfig.Rt.Exposure.ADAPT_BRIGHTEN.value(),
                CausticaConfig.Rt.Exposure.LOW_PERCENTILE.value(),
                CausticaConfig.Rt.Exposure.HIGH_PERCENTILE.value(),
                CausticaConfig.Rt.Exposure.STRIDE.value(),
                CausticaConfig.Rt.Exposure.CENTER_WEIGHT_SIGMA.value(),
                CausticaConfig.Rt.Exposure.CENTER_WEIGHT_FLOOR.value(),
                CausticaConfig.Rt.Exposure.SKY_WEIGHT_CAP.value(),
                CausticaConfig.Rt.Exposure.EMISSIVE_WEIGHT_CAP.value(),
                CausticaConfig.Rt.Exposure.PRE_EXPOSURE.value(),
                CausticaConfig.Rt.FrameStats.ENABLED.value(),
                CausticaConfig.Rt.Tonemap.GAMMA.value());
    }

    private static int maxBounces() {
        return CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    // Modulus of the world-pinned procedural domain anchor. Documented engine constant, not a per-surface
    // tunable: a very low-frequency field could alias across it where the wave spectrum does not.
    private static final int PROCEDURAL_ANCHOR_MASK = 4095;
    // Renderer look metadata is exposure/LMT only; scene providers own their photometric calibration.
    private static final RtLookPackage LOOK = RtLookPackage.loadDefault();
    // Sign applied to primary-ray jitter. NRD receives that actual offset; DLSS-RR receives its inverse
    // at evaluation, matching the validated Vulkan clip-space convention.
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    // Monotonic per-composite frame counter used for cache eviction, shader sampling, and diagnostics.
    private volatile long frameCounter;

    public long frameCounter() {
        return frameCounter;
    }

    private final VulkanDeviceContext context;
    private final RtProgramBackend programs;
    private final RtRetainedSceneBackend scenes;
    private final RtPassSchedulerBackend passes;
    private final EngineSessionServices services;
    private final RtFramePresenter presenter;
    private final DlssRayReconstruction rayReconstruction;
    private final RtUpscaler upscaler;
    private final RtDenoiserState denoiser;
    private long previousDenoiserFrameNanos;
    private float previousDenoiserJitterX;
    private float previousDenoiserJitterY;
    private final Matrix4f previousDenoiserWorldToView = new Matrix4f();
    private final Matrix4f previousDenoiserViewToClip = new Matrix4f();
    private final RtTelemetry telemetry;
    // recordFrame mutates this only on the render thread; each renderer owns an independent sequence.
    private final RtJitter jitter;
    // World push data lives in a host-visible BDA buffer allocated for the frame that writes it; only its
    // address and a small hot subset are pushed inline (the full generated structure exceeds NVIDIA's
    // 256-byte push-constant ceiling). The allocation retires with the frame, so no write ever lands on
    // memory the GPU still reads.
    private final RtFrameResources frameResources;

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
    private long lastLightingFrame = -1L;
    private SceneOrigin lastLightingOrigin;
    private SceneId lastEntryScene;
    private float previousProceduralTime;
    private boolean proceduralTimeValid;
    private boolean loggedActive;

    // Camera captured each frame from the host adapter (unjittered projection, rotation, and position).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private final Matrix4f historyProjection = new Matrix4f();
    private final Matrix4f historyViewRotation = new Matrix4f();
    private FrameSnapshot frameSnapshot;

    private RtGpuExecutor.GraphicsUse pendingGraphicsUse;
    private RtRetainedSceneBackend.PreparedTrace currentTrace;

    public RtFrameRenderer(VulkanDeviceContext context, RtProgramBackend programs, RtRetainedSceneBackend scenes,
                    RtPassSchedulerBackend passes, EngineSessionServices services,
                    RtFramePresenter presenter, DlssRayReconstruction rayReconstruction,
                    RtUpscaler upscaler,
                    DenoiserBackendFactory denoiserFactory, RtDenoisingSettings denoisingSettings,
                    RtTelemetry telemetry) {
        this.context = Objects.requireNonNull(context, "context");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.services = Objects.requireNonNull(services, "services");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.rayReconstruction = Objects.requireNonNull(rayReconstruction, "rayReconstruction");
        this.upscaler = Objects.requireNonNull(upscaler, "upscaler");
        this.denoiser = new RtDenoiserState(denoiserFactory, denoisingSettings);
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.jitter = new RtJitter();
        this.frameResources = new RtFrameResources(presenter, rayReconstruction, upscaler, LOOK, exposureSettings());
    }

    /** Applies one mutually exclusive output route at the next frame boundary. */
    public void configureDenoising(RtDenoisingSettings settings) {
        settings = Objects.requireNonNull(settings, "settings");
        if (denoiser.configured(settings)) return;
        context.waitIdle();
        denoiser.configureAfterIdle(settings);
        resetSceneHistory();
    }

    /** Read-only access to the auto-exposure controller, for diagnostics (F3 entry, frame stats log). */
    public RtExposure exposure() {
        return presentationResources().exposure();
    }

    private TraceResources traceResources() {
        return frameResources.trace();
    }

    private TraceImages traceImages() {
        return traceResources().images();
    }

    private TraceExtent traceExtent() {
        return traceResources().extent();
    }

    private PresentationResources presentationResources() {
        return frameResources.presentation();
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
        context.backend().assertRenderThread();
        if (presentationResources().exposure().image() == null || pendingGraphicsUse != null) {
            return false;
        }

        TraceExtent extent = traceExtent();
        long pixelCount = Math.multiplyExact((long) extent.displayWidth(), (long) extent.displayHeight());
        long rgbaBytes = Math.multiplyExact(pixelCount, 4L * Short.BYTES);
        long totalBytes = Math.addExact(rgbaBytes, Float.BYTES);
        if (pixelCount > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("EXR capture is too large for a Java array: "
                    + extent.displayWidth() + "x" + extent.displayHeight());
        }

        // All ordinary frame commands have been submitted before the F2 key is handled. Drain them before
        // a private one-shot copy so rrOutput and the exposure image describe the same completed frame.
        context.waitIdle();
        GpuBuffer readback = context.createReadbackBuffer(totalBytes, "residual-exposure EXR readback");
        try {
            context.submitSync(cmd -> recordExrReadback(context, cmd, readback, rgbaBytes));
            readback.invalidate();

            float residualExposure = MemoryUtil.memGetFloat(readback.mapped() + rgbaBytes);
            RtExposure.CaptureMetadata exposureMetadata = presentationResources().exposure().captureMetadata(residualExposure);
            short[] exposedRgba = new short[Math.toIntExact(pixelCount * 4L)];
            for (int sample = 0; sample < exposedRgba.length; sample++) {
                short storedHalf = MemoryUtil.memGetShort(readback.mapped() + (long) sample * Short.BYTES);
                float value = Float.float16ToFloat(storedHalf);
                if ((sample & 3) != 3) {
                    value *= residualExposure;
                }
                // Residual exposure is expected to keep this seam comfortably centred in fp16. Clamp only
                // true outliers/infinities so a pathological light cannot poison a grading application.
                value = Math.clamp(value, -65504.0f, 65504.0f);
                exposedRgba[sample] = Float.floatToFloat16(value);
            }

            RtOpenExrWriter.write(outputPath, extent.displayWidth(), extent.displayHeight(), exposedRgba,
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

    private void recordExrReadback(VulkanDeviceContext ctx, VkCommandBuffer cmd, GpuBuffer readback, long exposureOffset) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     "residual-exposure EXR readback")) {
            VkImageMemoryBarrier2.Buffer imageBarriers = VkImageMemoryBarrier2.calloc(2, stack);
            imageBarriers.get(0).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                            | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(traceImages().reconstructedColor().image());
            imageBarriers.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            imageBarriers.get(1).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                            | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(presentationResources().exposure().image().image());
            imageBarriers.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(imageBarriers));

            VkBufferImageCopy2.Buffer sceneCopy = VkBufferImageCopy2.calloc(1, stack);
            sceneCopy.get(0).sType$Default();
            sceneCopy.get(0).bufferOffset(0L);
            sceneCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            sceneCopy.get(0).imageExtent().set(traceExtent().displayWidth(), traceExtent().displayHeight(), 1);
            VK13.vkCmdCopyImageToBuffer2(cmd, VkCopyImageToBufferInfo2.calloc(stack).sType$Default()
                    .srcImage(traceImages().reconstructedColor().image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .dstBuffer(readback.handle()).pRegions(sceneCopy));

            VkBufferImageCopy2.Buffer exposureCopy = VkBufferImageCopy2.calloc(1, stack);
            exposureCopy.get(0).sType$Default();
            exposureCopy.get(0).bufferOffset(exposureOffset);
            exposureCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            exposureCopy.get(0).imageExtent().set(1, 1, 1);
            VK13.vkCmdCopyImageToBuffer2(cmd, VkCopyImageToBufferInfo2.calloc(stack).sType$Default()
                    .srcImage(presentationResources().exposure().image().image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .dstBuffer(readback.handle()).pRegions(exposureCopy));

            VkMemoryBarrier2.Buffer hostBarrier = VkMemoryBarrier2.calloc(1, stack);
            hostBarrier.get(0).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_HOST_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_HOST_READ_BIT);
            VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default()
                    .pMemoryBarriers(hostBarrier));
        }
    }

    /**
     * Whether the current frame must retain source rasterization while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate. This
     * prevents the source renderer from being suppressed before a deliberately
     * transient {@link #composite} return. Such a return is not a renderer failure and must not trip the host's
     * permanent safety latch.</p>
     */
    public boolean requiresSourceWorldFallback() {
        return programs.active() == null;
    }

    /**
     * Complete the material epoch while startup is retaining source presentation. The runtime calls this
     * only after the scene update has applied the pending full clear, so activation depends on resource
     * readiness rather than an additional tick or rendered frame.
     */
    public boolean completeStartupBoundary() {
        services.progress();
        return programs.active() != null;
    }

    /** Capture the immutable host frame for the next composite. Called from the host render adapter. */
    public void captureFrame(FrameSnapshot snapshot) {
        frameSnapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** Reset exposure filtering after an explicit render-state invalidation such as F3+A. */
    public void resetExposureHistory() {
        presentationResources().exposure().requestReset();
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — host overlay raster passes reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    /**
     * Invalidate the published presentation snapshot at the start of host rendering. Menu and loading
     * frames do not call {@link #composite()}, so retaining the previous scene snapshot would present stale
     * HDR content instead of selecting the SDR-to-PQ path.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        telemetry.beginRenderFrame();
        telemetry.beginFrameIfInactive();
        presenter.beginFrame();
        frameSnapshot = null;
    }

    /** Records UI passes into a host command buffer after the host has made its UI layer available. */
    public void recordUiPasses(VkCommandBuffer commandBuffer, GpuImage uiLayer) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(uiLayer, "uiLayer");
        if (pendingGraphicsUse == null || currentTrace == null || frameSnapshot == null) {
            throw new IllegalStateException("no retained frame is available for UI recording");
        }
        passes.beginFrame(passFrame(commandBuffer, pendingGraphicsUse,
                new RtPassSchedulerBackend.UiState(uiLayer, mvCurProjView.get(new float[16]),
                        currentTrace.tlasDescriptor())));
        try {
            services.passes().recordUi();
        } finally {
            passes.endFrame();
        }
    }

    /** Signals this frame's completion reservation after the host has recorded any UI consumers. */
    public void finishGraphicsUse() {
        RtGpuExecutor.GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        pendingGraphicsUse = null;
        currentTrace = null;
        GraphicsSubmission submission = context.backend().createGraphicsSubmission();
        context.gpuExecutor().resolveGraphicsUse(submission, graphicsUse);
    }

    public void endFrame() {
        telemetry.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        frameCounter++; // renderer-local frame serial used by per-frame source rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        context.gpuExecutor().throwIfFailed();
        services.progress();
        FrameSnapshot snapshot = frameSnapshot;
        if (snapshot == null) {
            // No scene was captured this frame. Skip RT so the present path falls back to the host image.
            return false;
        }
        try {
            if (!ensurePresentationResources(context, width, height)) {
                return false;
            }
            RtProgramBackend.Published active = ensureWorld(context);
            if (active == null) {
                return false;
            }
            SceneId entryScene = snapshot.view().entryScene();
            if (entryScene != lastEntryScene) {
                resetSceneHistory();
                lastEntryScene = entryScene;
            }
            SceneOrigin lightingOrigin = snapshot.sceneOrigin();
            boolean lightingHistoryContinuous = mvHasPrev && lastLightingFrame + 1L == frameCounter
                    && Objects.equals(lastLightingOrigin, lightingOrigin) && !isLightingCameraCut(snapshot);
            boolean localLightingHistoryContinuous = lightingHistoryContinuous
                    && isLightingCameraStationary(snapshot);
            if (!lightingHistoryContinuous) {
                rayReconstruction.resetHistory();
                upscaler.resetHistory();
                denoiser.resetHistory();
            }
            updateMotion(snapshot);
            recordFrame(context, active, nativeColorImage, snapshot, lightingHistoryContinuous,
                    localLightingHistoryContinuous);
            lastLightingFrame = frameCounter;
            lastLightingOrigin = lightingOrigin;
            if (!loggedActive) {
                loggedActive = true;
                LOGGER.info("RT composite active: {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            // A frame that dies mid-recording leaves its reservation unresolved and the retained scene
            // partially advanced. There is no defined state to resume from, so surface the original
            // fault instead of masking it behind source rasterization.
            presenter.invalidateRenderedFrame();
            LOGGER.error("RT composite failed", t);
            if (t instanceof RuntimeException runtime) throw runtime;
            if (t instanceof Error error) throw error;
            throw new IllegalStateException("RT composite failed", t);
        }
    }

    /** Build every display-sized resource while startup is still presenting the source renderer. */
    public boolean ensurePresentationResourcesReady(long sceneId, int width, int height) {
        if (sceneId == 0L) {
            return false;
        }
        try {
            return ensurePresentationResources(context, width, height);
        } catch (IOException failure) {
            // Shader and pipeline bring-up has no partial success worth presenting around.
            throw new IllegalStateException("RT presentation resource bring-up failed", failure);
        }
    }

    private boolean ensurePresentationResources(VulkanDeviceContext ctx, int width, int height)
            throws IOException {
        presentationResources().configureExposure(exposureSettings());
        frameResources.ensurePresentationPipelines(ctx);
        if (frameResources.ensureSized(ctx, width, height, denoiser.settings().route(),
                denoiser::closeBackendAfterIdle)) {
            mvHasPrev = false;
            proceduralTimeValid = false;
            upscaler.resetHistory();
            denoiser.resetHistory();
        }
        ensureDenoiserBackend();
        return true;
    }

    private void ensureDenoiserBackend() {
        denoiser.ensureBackend(new DenoiserExtent(
                traceExtent().renderWidth(), traceExtent().renderHeight()));
    }

    private RtProgramBackend.Published ensureWorld(VulkanDeviceContext ctx) {
        return programs.active();
    }
    /**
     * Invalidates temporal consumers before the host replaces pack-owned images. Descriptor leases and
     * program registrations retain their image epochs until the graphics timeline retires them.
     */
    public void onResourceReloadStart() {
        resetSceneHistory();
    }

    /**
     * Resume resource convergence after the host rejected a reload before replacing its pack-owned images.
     * The pre-reload phase has already detached the old descriptors, so the next world bring-up rebuilds
     * them against the still-current host resources.
     */
    public void onResourceReloadFailed() {
    }

    /** Notify runtime-activation-scoped passes after the host has published the replacement pack epoch. */
    public void onResourcePackApplied() {
        services.progress();
    }

    /** Invalidate renderer state that cannot cross a provider-requested scene discontinuity. */
    public void resetSceneHistory() {
        resetExposureHistory();
        rayReconstruction.resetHistory();
        upscaler.resetHistory();
        presenter.resetSceneHistory();
        mvHasPrev = false;
        lastLightingFrame = -1L;
        lastLightingOrigin = null;
        proceduralTimeValid = false;
        denoiser.resetHistory();
        previousDenoiserFrameNanos = 0L;
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

    private boolean isLightingCameraCut(FrameSnapshot snapshot) {
        snapshot.copyProjectionTo(historyProjection);
        snapshot.copyViewRotationTo(historyViewRotation);
        float forwardDot = frameViewRotation.m02() * historyViewRotation.m02()
                + frameViewRotation.m12() * historyViewRotation.m12()
                + frameViewRotation.m22() * historyViewRotation.m22();
        boolean projectionCut = relativeDifference(frameProjection.m00(), historyProjection.m00()) > 0.1f
                || relativeDifference(frameProjection.m11(), historyProjection.m11()) > 0.1f;
        double dx = snapshot.cameraX() - mvPrevCamX;
        double dy = snapshot.cameraY() - mvPrevCamY;
        double dz = snapshot.cameraZ() - mvPrevCamZ;
        double distanceMetersSquared = (dx * dx + dy * dy + dz * dz)
                * snapshot.metersPerWorldUnit() * snapshot.metersPerWorldUnit();
        return forwardDot < 0.70710677f || projectionCut || distanceMetersSquared > 64.0;
    }

    private boolean isLightingCameraStationary(FrameSnapshot snapshot) {
        double dx = snapshot.cameraX() - mvPrevCamX;
        double dy = snapshot.cameraY() - mvPrevCamY;
        double dz = snapshot.cameraZ() - mvPrevCamZ;
        return dx * dx + dy * dy + dz * dz <= 1.0e-12
                && frameProjection.equals(historyProjection, 1.0e-6f)
                && frameViewRotation.equals(historyViewRotation, 1.0e-6f)
                && !snapshot.proceduralSurfaceAnimationEnabled();
    }

    private static float relativeDifference(float first, float second) {
        return Math.abs(first - second) / Math.max(Math.max(Math.abs(first), Math.abs(second)), 1.0e-6f);
    }

    private boolean recordTemporalDenoiser(VulkanDeviceContext ctx, VkCommandBuffer commandBuffer,
                                           MemoryStack stack, RtGpuExecutor.GraphicsUse graphicsUse,
                                           boolean historyContinuous, float jitterX, float jitterY,
                                           Float3 cameraOffset, GpuImage output) {
        DenoiserReset frameReset = denoiser.frameReset(historyContinuous);
        boolean reset = frameReset == DenoiserReset.CLEAR_AND_RESTART;
        Matrix4f previousWorldToView = nrdPreviousWorldToView(reset, frameViewRotation,
                previousDenoiserWorldToView, mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ);
        Matrix4f previousViewToClip = reset ? frameProjection : previousDenoiserViewToClip;
        float previousJitterX = reset ? jitterX : previousDenoiserJitterX;
        float previousJitterY = reset ? jitterY : previousDenoiserJitterY;
        long now = System.nanoTime();
        float frameTimeMilliseconds = previousDenoiserFrameNanos == 0L
                ? 0.0f : (now - previousDenoiserFrameNanos) * 1.0e-6f;
        // The trace writes old = new + MV in render pixels; NRD consumes the same sign in screen units.
        float inverseWidth = 1.0f / traceExtent().renderWidth();
        float inverseHeight = 1.0f / traceExtent().renderHeight();
        DenoiserCommonSettings common = new DenoiserCommonSettings(
                frameViewRotation.get(new float[16]), previousWorldToView.get(new float[16]),
                frameProjection.get(new float[16]), previousViewToClip.get(new float[16]),
                jitterX, jitterY, previousJitterX, previousJitterY,
                inverseWidth, inverseHeight, 1.0f, NRD_DENOISING_RANGE,
                0.03f, 0.2f, frameTimeMilliseconds, (int) (frameCounter & 0x7fff_ffffL),
                false, true, false,
                frameReset);
        DenoiserInputs inputs = new DenoiserInputs(
                denoiserImage(traceImages().diffuseRadianceHitDistance()),
                denoiserImage(traceImages().specularRadianceHitDistance()),
                denoiserImage(traceImages().normalRoughness()),
                denoiserImage(traceImages().nrdViewZ()),
                denoiserImage(traceImages().nrdMotion()),
                denoiserImage(traceImages().denoisedDiffuseRadianceHitDistance()),
                denoiserImage(traceImages().denoisedSpecularRadianceHitDistance()),
                Optional.of(denoiserImage(traceImages().nrdDisocclusionThresholdMix())), Optional.empty());

        GpuBuffer frameBuffer = ctx.createBuffer(NrdPlaneFrameData.BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "NRD stable-plane frame");
        graphicsUse.whenComplete(frameBuffer::destroy);
        ByteBuffer frameData = MemoryUtil.memByteBuffer(frameBuffer.mapped(), NrdPlaneFrameData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new NrdPlaneFrameData(mvCurProjView, mvPushMatrix,
                new NrdPlaneFrameData.Float3(cameraOffset.x(), cameraOffset.y(), cameraOffset.z()),
                presentationResources().exposure().preExposure(),
                new NrdPlaneFrameData.Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                128.0f, traceExtent().renderWidth(), traceExtent().renderHeight()).write(frameData);
        frameBuffer.flush(0L, NrdPlaneFrameData.BYTE_SIZE);

        long stablePlaneAddress = traceResources().stablePlaneBuffer().deviceAddress().value();
        long frameAddress = frameBuffer.deviceAddress().value();
        RtNrdComposePipeline.Exchange exchange = new RtNrdComposePipeline.Exchange(output,
                traceImages().diffuseRadianceHitDistance(), traceImages().specularRadianceHitDistance(),
                traceImages().normalRoughness(), traceImages().nrdViewZ(), traceImages().nrdMotion(),
                traceImages().nrdDisocclusionThresholdMix(),
                traceImages().denoisedDiffuseRadianceHitDistance(),
                traceImages().denoisedSpecularRadianceHitDistance(), traceImages().nrdStableRadiance());
        for (int plane = RtDenoiserState.PLANE_COUNT - 1; plane >= 0; plane--) {
            presentationResources().nrdComposePipeline().prepare(commandBuffer,
                    stablePlaneAddress, frameAddress, plane, nrdSignalEncoding(), exchange);
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            ctx.invalidateDescriptorHeapsForExternalCommand(commandBuffer);
            try {
                denoiser.backend(plane).record(new DenoiserFrame(commandBuffer.address(), common, inputs));
            } finally {
                ctx.bindDescriptorHeaps(commandBuffer);
            }
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            presentationResources().nrdComposePipeline().merge(commandBuffer,
                    stablePlaneAddress, frameAddress, plane, nrdSignalEncoding(), exchange);
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
        }
        denoiser.frameRecorded();
        previousDenoiserFrameNanos = now;
        previousDenoiserJitterX = jitterX;
        previousDenoiserJitterY = jitterY;
        previousDenoiserWorldToView.set(frameViewRotation);
        previousDenoiserViewToClip.set(frameProjection);
        return reset;
    }

    private DenoiserImage denoiserImage(GpuImage image) {
        return new DenoiserImage(image.image(), image.format(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                new DenoiserExtent(image.width(), image.height()));
    }

    private int nrdSignalEncoding() {
        return denoiser.settings().signalEncoding()
                == DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE ? 1 : 0;
    }

    static Matrix4f nrdPreviousWorldToView(boolean reset, Matrix4f currentWorldToView,
                                           Matrix4f previousWorldToView,
                                           float currentMinusPreviousX,
                                           float currentMinusPreviousY,
                                           float currentMinusPreviousZ) {
        return reset ? new Matrix4f(currentWorldToView) : new Matrix4f(previousWorldToView)
                .translate(currentMinusPreviousX, currentMinusPreviousY, currentMinusPreviousZ);
    }

    private void recordFrame(VulkanDeviceContext ctx, RtProgramBackend.Published program, long nativeColorImage,
                             FrameSnapshot snapshot, boolean lightingHistoryContinuous,
                             boolean localLightingHistoryContinuous) {
        long dstImage = nativeColorImage;
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        RtGpuExecutor.GraphicsUse graphicsUse = gpuExecutor.beginGraphicsUse(submission);
        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        presentationResources().exposure().beginFrame(graphicsUseWaiter);
        pendingGraphicsUse = graphicsUse;
        GpuBuffer continuationQueue = null;
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        int debugView = debugView();
        SceneOrigin sceneOrigin = snapshot.sceneOrigin();
        SceneId entryScene = snapshot.view().entryScene();
        boolean passFrameOpen = false;
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // The selected route owns jitter and reconstruction. Debug presentation observes the completed
            // route and never changes its sampling or history behavior.
            DenoiserRoute denoiserRoute = denoiser.settings().route();
            boolean rrPath = denoiserRoute == DenoiserRoute.RAY_RECONSTRUCTION
                    && rayReconstruction.enabled();
            float jitterX = 0f;
            float jitterY = 0f;
            if (denoiserRoute != DenoiserRoute.RAW) {
                jitter.prepare(traceExtent().renderWidth(), traceExtent().renderHeight(),
                        traceExtent().displayWidth());
                jitterX = jitter.jitterPixelsX() * jitterSignX();
                jitterY = jitter.jitterPixelsY() * jitterSignY();
            }

            GpuBuffer pushBuf = ctx.createBuffer(WORLD_PUSH_SIZE,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push");
            graphicsUse.whenComplete(pushBuf::destroy);
            continuationQueue = traceResources().acquireContinuationQueue(ctx, graphicsUse);
            VK10.vkCmdFillBuffer(cmd, continuationQueue.handle(), 0L, continuationQueue.size(), 0);
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped(), WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            int flags = snapshot.proceduralSurfaceAnimationEnabled() ? 0b10000 : 0;
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
            double proceduralPeriod = PROCEDURAL_ANCHOR_MASK + 1.0;
            Float3 proceduralDomainOffset = new Float3(sceneOrigin.wrappedX(proceduralPeriod),
                    sceneOrigin.wrappedY(proceduralPeriod), sceneOrigin.wrappedZ(proceduralPeriod));
            EnvironmentBinding<?> environment = scenes.content(entryScene).environment();
            EnvironmentPush environmentState = environmentPush(environment,
                    environment == null ? 0 : services.programs().resolve(environment.implementation()));
            Float3 cameraOffset = new Float3(sceneOrigin.relativeX(snapshot.cameraX()),
                    sceneOrigin.relativeY(snapshot.cameraY()), sceneOrigin.relativeZ(snapshot.cameraZ()));

            new WorldPushData(
                    frameInvViewProj,
                    cameraOffset,
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    new Float2(jitterX, jitterY),
                    flags,
                    maxBounces(),
                    time,
                    proceduralDomainOffset,
                    mvCurProjView,
                    previousTime,
                    // Must be the SAME value the exposure resolve divides out this frame (it reads it
                    // from the same RtExposure accessor), or the two stop cancelling.
                    presentationResources().exposure().preExposure(),
                    environmentState.bindingData(),
                    environmentState.implementation()
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            TlasBuilder.Prepared frameTlas;
            try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.prepareTlas")) {
                frameTlas = scenes.prepareTlas(entryScene, sceneOrigin, graphicsUse);
            }
            try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.recordTlas")) {
                TlasBuilder.record(ctx, cmd, frameTlas);
            }
            VulkanBarriers.memoryBarrier(cmd, stack);
            RtRetainedSceneBackend.PreparedLighting lighting;
            try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.prepareLighting")) {
                lighting = scenes.prepareLighting(entryScene,
                        new RtRetainedSceneBackend.LightingFrame(traceExtent().renderWidth(), traceExtent().renderHeight(),
                                frameCounter, (float) snapshot.metersPerWorldUnit(),
                                lightingHistoryContinuous, localLightingHistoryContinuous), cmd, graphicsUse);
            }
            boolean lightingFinished = false;
            try {
                RtRetainedSceneBackend.PreparedTrace trace;
                try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.prepareTrace")) {
                    trace = scenes.prepareTrace(entryScene, sceneOrigin,
                            program.pipeline(), frameTlas.accel.handle, graphicsUse);
                }
                currentTrace = trace;
                ByteBuffer roots = stack.calloc(RtBindings.WORLD_PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
                writeFrameRoots(roots, pushBuf.deviceAddress(), snapshot, continuationQueue);
                program.writeCompositionDataAddress(roots);
                trace.writeWorldRoots(roots);

                passes.beginFrame(passFrame(cmd, graphicsUse, null));
                passFrameOpen = true;
                services.passes().recordWorldResources();
                VulkanBarriers.worldResourcesToPrimary(cmd, stack);

                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.tracePrimary")) {
                    program.pipeline().trace(cmd, traceExtent().renderWidth(), traceExtent().renderHeight(),
                            roots, 0, trace.hitTable());
                }
                VulkanBarriers.primaryToIndirect(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.traceIndirect")) {
                    program.pipeline().trace(cmd, traceExtent().renderWidth(), traceExtent().renderHeight(),
                            roots, 1, trace.hitTable());
                }
                VulkanBarriers.memoryBarrier(cmd, stack); // RT writes visible to reconstruction reads
                scenes.finishLighting(entryScene, lighting, cmd, graphicsUse);
                lightingFinished = true;
            } catch (Throwable failure) {
                if (!lightingFinished) scenes.abandonLighting(entryScene, lighting);
                throw failure;
            }
            boolean outputReady = false;
            if (denoiserRoute == DenoiserRoute.RAY_RECONSTRUCTION) {
                if (rrPath) {
                    ctx.invalidateDescriptorHeapsForExternalCommand(cmd);
                    try {
                        if (rayReconstruction.ensureFeature(cmd, traceExtent().renderWidth(),
                                traceExtent().renderHeight(), traceExtent().displayWidth(),
                                traceExtent().displayHeight())) {
                            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.dlssRr")) {
                                outputReady = rayReconstruction.evaluate(cmd, traceImages().traceColor(),
                                        traceImages().depth(), traceImages().motion(),
                                        traceImages().diffuseAlbedo(), traceImages().specularAlbedo(),
                                        traceImages().normalRoughness(), traceImages().specularMotion(),
                                        traceImages().reconstructedColor(), traceExtent().renderWidth(),
                                        traceExtent().renderHeight(), traceExtent().displayWidth(),
                                        traceExtent().displayHeight(), -jitterX, -jitterY);
                            }
                        }
                    } finally {
                        ctx.bindDescriptorHeaps(cmd);
                    }
                }
            } else if (denoiserRoute == DenoiserRoute.TEMPORAL_DENOISER) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "NRD evaluate");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.nrd")) {
                    boolean upscale = upscaler.configured();
                    GpuImage composeOutput = upscale
                            ? traceImages().traceColor() : traceImages().reconstructedColor();
                    boolean temporalReset = recordTemporalDenoiser(ctx, cmd, stack, graphicsUse,
                            lightingHistoryContinuous, jitterX, jitterY, cameraOffset, composeOutput);
                    if (!upscale) {
                        outputReady = true;
                    } else {
                        VulkanBarriers.memoryBarrier(cmd, stack);
                        ctx.invalidateDescriptorHeapsForExternalCommand(cmd);
                        try (RtDebugLabels.Scope ignoredUpscale = RtDebugLabels.scope(ctx, cmd, "temporal upscale");
                             RtTelemetry.Scope ignoredUpscaleStats = telemetry.frame().stage("frame.upscale")) {
                            outputReady = upscaler.record(new RtUpscaler.Frame(cmd, composeOutput,
                                    traceImages().depth(), traceImages().motion(),
                                    traceImages().reconstructedColor(),
                                    new RtUpscaler.Extent(traceExtent().renderWidth(), traceExtent().renderHeight(),
                                            traceExtent().displayWidth(), traceExtent().displayHeight()),
                                    jitterX, jitterY, temporalReset,
                                    presentationResources().exposure().preExposure()));
                        } finally {
                            ctx.bindDescriptorHeaps(cmd);
                        }
                    }
                }
            } else {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "raw trace copy");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.rawCopy")) {
                    copyImage(cmd, stack, traceImages().traceColor(), traceImages().reconstructedColor());
                    outputReady = true;
                }
            }

            if (!outputReady) {
                VulkanBarriers.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.upscale")) {
                    blitUpscale(cmd, stack, traceImages().traceColor(), traceImages().reconstructedColor());
                }
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // reconstructed output visible to exposure histogram

            // Auto-exposure meters the selected route's reconstructed output. This keeps temporal routes
            // stable and leaves RR exposure-independent as required by its integration contract; raw mode
            // deliberately meters its own noisy reference.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.exposure")) {
                presentationResources().exposure().record(ctx, cmd, stack, traceImages().reconstructedColor(),
                        traceImages().depth(), traceImages().diffuseAlbedo());
                presentationResources().exposure().recordStateReadback(cmd, stack);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // exposure image visible to downstream passes

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "post chain");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.postChain")) {
                services.passes().recordPostEffects();
            }
            RtToneLut displayLookLut = presentationResources().lookLut();
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.displayMap")) {
                presentationResources().displayPipeline().dispatch(cmd, presentationResources().displayImage(),
                        passes.sceneColor(), presentationResources().exposure().image(),
                        presentationResources().hdrDisplayImage(), presentationResources().sdrToneLut(),
                        presentationResources().hdrToneLut(), displayLookLut,
                        CausticaConfig.Rt.Hdr.enabled(), CausticaConfig.Rt.Tonemap.GAMMA.value(),
                        presentationResources().loadedHdrLutNits(), true);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // display output visible to debug composite

            if (debugView != 0) {
                // Debug content is composited only after the real scene has completed trace, reconstruction,
                // exposure, and display mapping. It therefore observes the renderer without perturbing
                // exposure history or feeding literal diagnostic colors through ACES. Debug presentation
                // remains SDR for now; a PQ swapchain uses the existing SDR->PQ conversion path.
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "debug present");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.debugPresent")) {
                    presentationResources().debugPresentPipeline().dispatch(cmd,
                            presentationResources().displayImage(), traceImages().normalRoughness(),
                            traceImages().diffuseAlbedo(), traceImages().depth(), traceImages().motion(),
                            traceImages().specularAlbedo(), traceImages().specularMotion(),
                            traceImages().reconstructedColor(), presentationResources().exposure().image(),
                            traceImages().traceColor(), traceImages().stablePlaneMetadata(),
                            presentationResources().exposure().stateBuffer(), debugView,
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_SIGMA.value(),
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_FLOOR.value());
                }
            }
            VulkanBarriers.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.copyOutput")) {
                VK13.vkCmdCopyImage2(cmd, VkCopyImageInfo2.calloc(stack).sType$Default()
                        .srcImage(presentationResources().displayImage().image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .dstImage(dstImage).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .pRegions(copyRegion(stack, traceExtent().displayWidth(), traceExtent().displayHeight())));
            }
            VulkanBarriers.memoryBarrier(cmd, stack);
            passes.endFrame();
            passFrameOpen = false;
        } finally {
            if (passFrameOpen) passes.endFrame();
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        submission.execute(cmd);
        graphicsUse.commandsAccepted();
        // Submission makes every frame-owned address reachable until the final overlay consumer.
        presentationResources().exposure().markStateReadbackUse(graphicsUse);
        presenter.publish(new RtFramePresenter.RenderedFrame(
                presentationResources().hdrDisplayImage(), traceImages().motion(), traceImages().depth(),
                traceExtent().renderWidth(), traceExtent().renderHeight(), mvCurProjView, mvPushMatrix,
                CausticaConfig.Rt.Hdr.enabled() && debugView == 0));
    }

    private RtPassSchedulerBackend.FrameState passFrame(
            VkCommandBuffer commandBuffer, RtGpuExecutor.GraphicsUse graphicsUse,
            RtPassSchedulerBackend.UiState ui) {
        return new RtPassSchedulerBackend.FrameState(commandBuffer, graphicsUse, frameCounter,
                frameSnapshot.view(), frameSnapshot.timeSeconds(), frameSnapshot.metersPerWorldUnit(),
                traceExtent().renderWidth(), traceExtent().renderHeight(), traceImages().reconstructedColor(),
                presentationResources().exposure().image(), presentationResources().postColorA(),
                presentationResources().postColorB(), ui);
    }

    private void writeFrameRoots(ByteBuffer roots, VulkanDeviceAddress worldPushAddress, FrameSnapshot snapshot,
                                 GpuBuffer continuationQueue) {
        ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
        int base = roots.position();
        target.putLong(base + RtBindings.WORLD_PUSH_ADDRESS_OFFSET, worldPushAddress.value());
        target.putLong(base + RtBindings.WORLD_PATH_QUEUE_ADDRESS_OFFSET,
                continuationQueue.deviceAddress().value());
        target.putFloat(base + RtBindings.WORLD_RECONSTRUCTION_MICRO_JITTER_SCALE_OFFSET,
                denoiser.settings().route() == DenoiserRoute.RAY_RECONSTRUCTION ? 0.1f : 0.0f);
        target.putLong(base + RtBindings.WORLD_STABLE_PLANE_BUFFER_ADDRESS_OFFSET,
                traceResources().stablePlaneBuffer().deviceAddress().value());
        target.putInt(base + RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET, storageIndex(traceImages().traceColor()));
        target.putInt(base + RtBindings.WORLD_STABLE_PLANE_METADATA_IMAGE_INDEX_OFFSET,
                storageIndex(traceImages().stablePlaneMetadata()));
        target.putInt(base + RtBindings.WORLD_NORMAL_GUIDE_INDEX_OFFSET, storageIndex(traceImages().normalRoughness()));
        target.putInt(base + RtBindings.WORLD_ALBEDO_GUIDE_INDEX_OFFSET, storageIndex(traceImages().diffuseAlbedo()));
        target.putInt(base + RtBindings.WORLD_DEPTH_GUIDE_INDEX_OFFSET, storageIndex(traceImages().depth()));
        target.putInt(base + RtBindings.WORLD_MOTION_GUIDE_INDEX_OFFSET, storageIndex(traceImages().motion()));
        target.putInt(base + RtBindings.WORLD_SPECULAR_ALBEDO_GUIDE_INDEX_OFFSET,
                storageIndex(traceImages().specularAlbedo()));
        target.putInt(base + RtBindings.WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET,
                storageIndex(traceImages().specularMotion()));
        target.putInt(base + RtBindings.WORLD_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().diffuseRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().specularRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_NRD_VIEW_Z_INDEX_OFFSET,
                storageIndex(traceImages().nrdViewZ()));
        target.putInt(base + RtBindings.WORLD_DENOISED_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().denoisedDiffuseRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_DENOISED_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().denoisedSpecularRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_NRD_STABLE_RADIANCE_INDEX_OFFSET,
                storageIndex(traceImages().nrdStableRadiance()));
        target.putInt(base + RtBindings.WORLD_NRD_SIGNAL_ENCODING_OFFSET,
                denoiser.settings().signalEncoding() == DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
                        ? 1 : 0);
        ViewMedium medium = snapshot.view().medium();
        int implementation = medium instanceof ViewMedium.Volume<?, ?> volume
                ? services.programs().resolve(volume.implementation()) : 0;
        writeInitialVolumeRoots(roots, medium, implementation);
    }

    static void writeInitialVolumeRoots(ByteBuffer roots, ViewMedium medium,
                                        int implementation) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(medium, "medium");
        if (implementation < 0) throw new IllegalArgumentException("volume implementation must be non-negative");
        if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("world binding root has the wrong size");
        }
        ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
        int base = roots.position();
        target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET, 0);
        target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET, 0);
        target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET, 0L);
        target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET, 0L);
        if (implementation != 0) {
            if (!(medium instanceof ViewMedium.Volume<?, ?> volume)) {
                throw new IllegalArgumentException("active initial volume has no typed data");
            }
            target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET,
                    implementation);
            target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET, 1);
            target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET,
                    volume.bindingData().bits());
            target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET,
                    volume.instanceData().bits());
        }
    }

    static EnvironmentPush environmentPush(EnvironmentBinding<?> binding,
                                           int implementation) {
        if (binding == null) return new EnvironmentPush(0L, 0);
        if (implementation < 0) throw new IllegalArgumentException("environment implementation must be non-negative");
        return implementation == 0
                ? new EnvironmentPush(0L, 0)
                : new EnvironmentPush(binding.bindingData().bits(), implementation);
    }

    record EnvironmentPush(long bindingData, int implementation) { }

    private static int storageIndex(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    public void destroy() {
        denoiser.close();
        rayReconstruction.destroyAfterDeviceIdle();
        upscaler.destroyAfterDeviceIdle();
        presenter.invalidateRenderedFrame();
        frameResources.destroy();
        mvHasPrev = false;
        lastLightingFrame = -1L;
        lastLightingOrigin = null;
        lastEntryScene = null;
        proceduralTimeValid = false;
        loggedActive = false;
        frameSnapshot = null;
        currentTrace = null;
        pendingGraphicsUse = null;
        denoiser.resetHistory();
        previousDenoiserFrameNanos = 0L;
    }

    private static VkImageCopy2.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy2.Buffer region = VkImageCopy2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    private static void copyImage(VkCommandBuffer commandBuffer, MemoryStack stack,
                                  GpuImage source, GpuImage destination) {
        if (source.width() != destination.width() || source.height() != destination.height()) {
            throw new IllegalArgumentException("copied images must have matching extents");
        }
        VK13.vkCmdCopyImage2(commandBuffer, VkCopyImageInfo2.calloc(stack).sType$Default()
                .srcImage(source.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .dstImage(destination.image()).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .pRegions(copyRegion(stack, source.width(), source.height())));
    }

    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, GpuImage src, GpuImage dst) {
        VkImageBlit2.Buffer region = VkImageBlit2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width(), src.height(), 1);
        region.get(0).dstOffsets(1).set(dst.width(), dst.height(), 1);
        VK13.vkCmdBlitImage2(cmd, VkBlitImageInfo2.calloc(stack).sType$Default()
                .srcImage(src.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .dstImage(dst.image()).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .filter(VK10.VK_FILTER_LINEAR).pRegions(region));
    }

}
