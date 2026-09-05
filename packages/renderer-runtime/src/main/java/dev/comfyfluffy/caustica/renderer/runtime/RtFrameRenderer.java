package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsQueue;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;

import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;

import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
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
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyImageInfo2;
import org.lwjgl.vulkan.VkImageCopy2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.RtLookPackage;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationResources;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceImages;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.renderer.runtime.pass.RtPassSchedulerBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneLut;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

/**
 * Records trace, reconstruction, and presentation from one captured frame. Temporal inputs become
 * history only after submission; frame resources remain owned through the host's final UI consumer.
 */
public final class RtFrameRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtFrameRenderer.class);
    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Modulus of the world-pinned procedural domain anchor. Documented engine constant, not a per-surface
    // tunable: a very low-frequency field could alias across it where the wave spectrum does not.
    private static final int PROCEDURAL_ANCHOR_MASK = 4095;
    // Renderer look metadata is exposure/LMT only; scene providers own their photometric calibration.
    private static final RtLookPackage LOOK = RtLookPackage.loadDefault();
    // Host-frame serial also detects gaps where the host rendered without an RT composite.
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
    private final RtReconstruction reconstruction;
    private final RtTelemetry telemetry;
    private final RtGpuTiming gpuTiming;
    private final RtFrameResources frameResources;
    private RtRenderSettings settings;
    private final RtFrameHistory history = new RtFrameHistory();
    private FrameSnapshot frameSnapshot;
    private RtFrameInput currentFrame;
    private boolean loggedActive;
    private long debugCaptureFrameSerial = -1;
    private float debugCapturePreExposure;
    private RtDenoisingSettings debugCaptureDenoising;

    private GraphicsUse pendingGraphicsUse;
    private RtRetainedSceneBackend.PreparedTrace currentTrace;

    public RtFrameRenderer(VulkanDeviceContext context, RtProgramBackend programs, RtRetainedSceneBackend scenes,
                    RtPassSchedulerBackend passes, EngineSessionServices services,
                    RtFramePresenter presenter, DlssRayReconstruction rayReconstruction,
                    RtUpscaler upscaler,
                    DenoiserBackendFactory denoiserFactory, RtDenoisingSettings denoisingSettings,
                    RtTelemetry telemetry, RtRenderSettings settings) {
        this.settings = settings;
        this.context = Objects.requireNonNull(context, "context");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.passes = Objects.requireNonNull(passes, "passes");
        this.services = Objects.requireNonNull(services, "services");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.reconstruction = new RtReconstruction(context, rayReconstruction, upscaler,
                denoiserFactory, denoisingSettings, telemetry);
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.gpuTiming = new RtGpuTiming(context);
        this.frameResources = new RtFrameResources(presenter, rayReconstruction, upscaler, LOOK, settings.exposure());
    }

    public void configureSettings(RtRenderSettings settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    /** Applies one mutually exclusive output route at the next frame boundary. */
    public void configureDenoising(RtDenoisingSettings settings) {
        settings = Objects.requireNonNull(settings, "settings");
        if (reconstruction.configured(settings)) return;
        context.waitIdle();
        reconstruction.configureAfterIdle(settings);
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

        RtFrameCapture.exportResidualExposureExr(context, traceImages().reconstructedColor(), traceExtent(),
                presentationResources().exposure(), LOOK, frameCounter, outputPath);
        return true;
    }

    public record DebugImageCapture(String name, long frameSerial, int width, int height,
                                    int vulkanFormat, String encoding, float preExposure,
                                    String denoiserRoute, String signalEncoding) { }

    public static List<String> debugImageNames() {
        return List.of("reconstructed-color", "trace-color", "normal-roughness", "diffuse-albedo",
                "specular-albedo", "depth", "motion", "specular-motion", "nrd-view-z",
                "nrd-diffuse", "nrd-specular", "nrd-stable-radiance");
    }

    /** Synchronous render-thread capture of a submitted frame, with no display or exposure transform. */
    public DebugImageCapture exportLatestDebugImage(String name, Path output) throws IOException {
        context.backend().assertRenderThread();
        if (!debugImageNames().contains(name)) throw new IllegalArgumentException("Unknown debug image: " + name);
        if (debugCaptureFrameSerial < 0 || pendingGraphicsUse != null) return null;
        TraceImages images = traceImages();
        GpuImage image = switch (name) {
            case "reconstructed-color" -> images.reconstructedColor();
            case "trace-color" -> images.traceColor();
            case "normal-roughness" -> images.normalRoughness();
            case "diffuse-albedo" -> images.diffuseAlbedo();
            case "specular-albedo" -> images.specularAlbedo();
            case "depth" -> images.depth();
            case "motion" -> images.motion();
            case "specular-motion" -> images.specularMotion();
            case "nrd-view-z" -> images.nrdViewZ();
            case "nrd-diffuse" -> images.diffuseRadianceHitDistance();
            case "nrd-specular" -> images.specularRadianceHitDistance();
            case "nrd-stable-radiance" -> images.nrdStableRadiance();
            default -> throw new IllegalArgumentException(name);
        };
        String encoding = switch (name) {
            case "reconstructed-color", "trace-color" -> "RGB: ACEScg scene radiance * preExposure";
            case "nrd-stable-radiance" -> "RGB: ACEScg scene-linear radiance (unexposed)";
            case "normal-roughness" -> "RGB: world-space unit normal; A: roughness";
            case "diffuse-albedo", "specular-albedo" -> "RGB: dimensionless ACEScg BSDF estimate; A: 1";
            case "depth" -> "R: reverse-Z device depth (dimensionless)";
            case "motion", "specular-motion" -> "RG: previous minus current position in render pixels";
            case "nrd-view-z" -> "R: absolute view Z in scene distance units; invalid: 65504";
            case "nrd-diffuse", "nrd-specular" -> "RGB: demodulated scene-linear radiance; A: hit distance; scale: "
                    + (debugCaptureDenoising.route() == DenoiserRoute.TEMPORAL_DENOISER ? "1/4096 (peak limited to 250)" : "1")
                    + "; packing: "
                    + debugCaptureDenoising.signalEncoding().name();
            default -> throw new IllegalArgumentException(name);
        };
        if (debugCaptureDenoising.route() == DenoiserRoute.TEMPORAL_DENOISER
                && List.of("normal-roughness", "nrd-view-z", "nrd-diffuse", "nrd-specular").contains(name)) {
            encoding += "; NRD plane 0 input (last evaluated plane)";
        }
        DebugImageCapture result = new DebugImageCapture(name, debugCaptureFrameSerial,
                image.width(), image.height(), image.format(), encoding, debugCapturePreExposure,
                debugCaptureDenoising.route().name(), debugCaptureDenoising.signalEncoding().name());
        RtFrameCapture.exportRaw(context, image, output, java.util.Map.of(
                "causticaBuffer", name, "causticaFrame", Long.toString(result.frameSerial()),
                "causticaEncoding", encoding, "causticaVulkanFormat", Integer.toString(image.format()),
                "causticaPreExposure", Float.toString(result.preExposure()),
                "causticaDenoiserRoute", result.denoiserRoute(), "causticaSignalEncoding", result.signalEncoding(),
                "causticaChannels", "Native components in RGBA; absent G/B = 0, absent A = 1",
                "causticaOrientation", "Top row first; vertically flipped from Vulkan image rows"));
        return result;
    }

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
     * Invalidate the published presentation snapshot at the start of host rendering. Menu and loading
     * frames do not call {@link #composite()}, so retaining the previous scene snapshot would present stale
     * HDR content instead of selecting the SDR-to-PQ path.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        debugCaptureFrameSerial = -1;
        frameCounter++;
        telemetry.beginRenderFrame();
        telemetry.beginFrameIfInactive();
        presenter.beginFrame();
        frameSnapshot = null;
        currentFrame = null;
    }

    /** Records UI passes in an owned heap command buffer after the host UI layer is available. */
    public void recordUiPasses(GpuImage uiLayer) {
        Objects.requireNonNull(uiLayer, "uiLayer");
        if (pendingGraphicsUse == null || currentTrace == null || frameSnapshot == null) {
            throw new IllegalStateException("no retained frame is available for UI recording");
        }
        try (RtFrameCommands commands = new RtFrameCommands(context, gpuTiming, pendingGraphicsUse, telemetry.frameSerial())) {
            VkCommandBuffer commandBuffer = commands.heap("UI extensions");
            passes.beginFrame(passFrame(commandBuffer, pendingGraphicsUse,
                    new RtPassSchedulerBackend.UiState(uiLayer, currentFrame.projectionView().get(new float[16]),
                            currentTrace.tlasDescriptor())));
            try {
                services.passes().recordUi();
            } finally {
                passes.endFrame();
            }
            commands.submit(context.backend().createGraphicsSubmission(), pendingGraphicsUse);
        }
    }

    /** Signals this frame's completion reservation after the host has recorded any UI consumers. */
    public void finishGraphicsUse() {
        GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        pendingGraphicsUse = null;
        currentTrace = null;
        GraphicsSubmission submission = context.backend().createGraphicsSubmission();
        context.graphics().resolveGraphicsUse(submission, graphicsUse);
    }

    public void endFrame() {
        telemetry.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
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
            RtProgramBackend.Published active = programs.active();
            if (active == null) {
                return false;
            }
            if (history.changesScene(snapshot)) resetSceneHistory();
            recordFrame(context, active, nativeColorImage, snapshot);
            if (!loggedActive) {
                loggedActive = true;
                LOGGER.info("RT composite active: {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            presenter.invalidateRenderedFrame();
            try {
                finishGraphicsUse();
            } catch (Throwable resolutionFailure) {
                t.addSuppressed(resolutionFailure);
            }
            resetSceneHistory();
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
        presentationResources().configureExposure(settings.exposure());
        frameResources.ensurePresentationPipelines(ctx, settings.peakNits());
        if (frameResources.ensureSized(ctx, width, height, reconstruction.settings().route(),
                reconstruction::closeBackendAfterIdle)) {
            resetSceneHistory();
        }
        reconstruction.ensureBackend(traceExtent());
        return true;
    }

    /**
     * Invalidates temporal consumers before the host replaces pack-owned images. Resource owners and
     * program registrations retain their image epochs until the graphics timeline retires them.
     */
    public void onResourceReloadStart() {
        resetSceneHistory();
    }

    /** Notify runtime-activation-scoped passes after the host has published the replacement pack epoch. */
    public void onResourcePackApplied() {
        services.progress();
    }

    /** Invalidate renderer state that cannot cross a provider-requested scene discontinuity. */
    public void resetSceneHistory() {
        resetExposureHistory();
        reconstruction.resetHistory();
        presenter.resetSceneHistory();
        history.reset();
    }

    private void recordFrame(VulkanDeviceContext ctx, RtProgramBackend.Published program, long nativeColorImage,
                             FrameSnapshot snapshot) {
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        GraphicsQueue graphics = ctx.graphics();
        GraphicsUse graphicsUse = graphics.beginGraphicsUse();
        pendingGraphicsUse = graphicsUse;
        retainViewResources(snapshot.view().medium(), graphicsUse);
        GraphicsQueue.GraphicsUseWaiter graphicsUseWaiter = graphics.graphicsUseWaiter();
        presentationResources().exposure().beginFrame(graphicsUseWaiter, telemetry.frameSerial());
        currentFrame = history.capture(snapshot, frameCounter, System.nanoTime(), traceExtent(),
                reconstruction.settings().route(), exposure().preExposure(), settings.jitterSignX(), settings.jitterSignY());
        if (!currentFrame.historyContinuous()) reconstruction.resetHistory();
        int debugView = settings.debugView();
        try (RtFrameCommands commands = new RtFrameCommands(ctx, gpuTiming, graphicsUse, telemetry.frameSerial());
             MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = commands.heap("world resources and trace");
            recordTrace(ctx, cmd, stack, graphicsUse, program, currentFrame);
            var output = reconstruction.record(commands, stack, graphicsUse, currentFrame,
                    traceResources(), presentationResources());
            recordPostProcessing(ctx, commands.heap("post processing and display"), stack,
                    graphicsUse, output, nativeColorImage, debugView);
            commands.submit(submission, graphicsUse);
            debugCaptureFrameSerial = telemetry.frameSerial();
            debugCapturePreExposure = currentFrame.preExposure();
            debugCaptureDenoising = reconstruction.settings();
            history.submitted(currentFrame);
            reconstruction.submitted(currentFrame);
        }
        // Submission makes every frame-owned address reachable until the final overlay consumer.
        presentationResources().exposure().markStateReadbackUse(graphicsUse);
        presenter.publish(new RtFramePresenter.RenderedFrame(
                presentationResources().hdrDisplayImage(), traceImages().motion(), traceImages().depth(),
                traceExtent().renderWidth(), traceExtent().renderHeight(),
                new Matrix4f(currentFrame.projectionView()), new Matrix4f(currentFrame.previousProjectionView()),
                settings.hdr() && debugView == 0));
    }

    static void retainViewResources(ViewMedium medium, GpuFrameUse frameUse) {
        if (!(medium instanceof ViewMedium.Volume<?, ?> volume)) return;
        ResourceOwners owners = ResourceOwners.capture(List.of(
                volume.bindingData().resource(), volume.instanceData().resource()));
        try {
            frameUse.whenComplete(owners::close);
        } catch (RuntimeException | Error failure) {
            owners.close();
            throw failure;
        }
    }

    private void recordTrace(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack,
            GraphicsUse graphicsUse, RtProgramBackend.Published program, RtFrameInput frame) {
        FrameSnapshot snapshot = frame.snapshot();
        SceneOrigin sceneOrigin = snapshot.sceneOrigin();
        SceneId entryScene = snapshot.view().entryScene();
        GpuBuffer pushBuf = ctx.createBuffer(WORLD_PUSH_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push");
        graphicsUse.whenComplete(pushBuf::destroy);
        GpuBuffer pathScratch = traceResources().pathScratchBuffer();
        ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped(), WORLD_PUSH_SIZE);
        Matrix4f frameInvViewProj = new Matrix4f(frame.projectionView()).invert();
        int flags = snapshot.proceduralSurfaceAnimationEnabled() ? 0b10000 : 0;
        float time = (float) (snapshot.timeSeconds() % 3600.0);
        // Procedural domain anchor: the scene rebase origin reduced mod 4096 (kept small for shader
        // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so a
        // pattern stays fixed in the world as the player moves and the rebase origin shifts.
        double proceduralPeriod = PROCEDURAL_ANCHOR_MASK + 1.0;
        Float3 proceduralDomainOffset = new Float3(sceneOrigin.wrappedX(proceduralPeriod),
                sceneOrigin.wrappedY(proceduralPeriod), sceneOrigin.wrappedZ(proceduralPeriod));
        long publicationCutoff = telemetry.publicationCutoff();
        EnvironmentBinding<?> environment = scenes.content(entryScene, graphicsUse).environment();
        telemetry.frameAssembled(publicationCutoff);
        EnvironmentPush environmentState = environmentPush(environment,
                environment == null ? 0 : services.programs().resolve(environment.implementation()));

        new WorldPushData(
                frameInvViewProj,
                frame.cameraOffset(),
                (int) frameCounter,
                new Matrix4f(frame.previousProjectionView()),
                frame.cameraDelta(),
                new Float2(frame.jitterX(), frame.jitterY()),
                flags,
                settings.maxBounces(),
                time,
                proceduralDomainOffset,
                new Matrix4f(frame.projectionView()),
                frame.previousProceduralTime(),
                // Must be the SAME value the exposure resolve divides out this frame (it reads it
                // from the same RtExposure accessor), or the two stop cancelling.
                frame.preExposure(),
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
                            frame.historyContinuous()), cmd, graphicsUse);
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
            writeFrameRoots(roots, pushBuf.deviceAddress(), snapshot, pathScratch);
            program.writeCompositionDataAddress(roots);
            trace.writeWorldRoots(roots);

            passes.beginFrame(passFrame(cmd, graphicsUse, null));

            try {
                services.passes().recordWorldResources();
            } finally {
                passes.endFrame();
            }
            VulkanBarriers.worldResourcesToPrimary(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "build stable planes");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.buildStablePlanes")) {
                program.pipeline().trace(cmd, traceExtent().renderWidth(), traceExtent().renderHeight(),
                        roots, 0, trace.hitTable());
            }
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "local NEE bake");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.bakeLocal")) {
                scenes.bakeLocal(entryScene, cmd,
                        storageIndex(traceImages().nrdViewZ()), storageIndex(traceImages().motion()));
            }
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fill stable planes");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.fillStablePlanes")) {
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
    }

    private void recordPostProcessing(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack,
            GraphicsUse graphicsUse, dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage output, long dstImage, int debugView) {
        passes.beginFrame(passFrame(cmd, graphicsUse, null, output));
        try {
            VulkanBarriers.memoryBarrier(cmd, stack); // reconstructed output visible to exposure histogram

            // Auto-exposure meters the selected route's reconstructed output. This keeps temporal routes
            // stable and leaves RR exposure-independent as required by its integration contract; raw mode
            // deliberately meters its own noisy reference.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.exposure")) {
                presentationResources().exposure().record(ctx, cmd, stack, output,
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
                        settings.hdr(), settings.exposure().gamma(),
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
                            settings.exposure().centerWeightSigma(),
                            settings.exposure().centerWeightFloor());
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
        } finally {
            passes.endFrame();
        }
    }

    private RtPassSchedulerBackend.FrameState passFrame(
            VkCommandBuffer commandBuffer, GraphicsUse graphicsUse,
            RtPassSchedulerBackend.UiState ui) {
        return passFrame(commandBuffer, graphicsUse, ui, traceImages().reconstructedColor());
    }

    private RtPassSchedulerBackend.FrameState passFrame(VkCommandBuffer commandBuffer, GraphicsUse graphicsUse,
                                                         RtPassSchedulerBackend.UiState ui, GpuImage output) {
        return new RtPassSchedulerBackend.FrameState(commandBuffer, graphicsUse, frameCounter,
                currentFrame.snapshot().view(), currentFrame.snapshot().timeSeconds(),
                currentFrame.snapshot().metersPerWorldUnit(),
                traceExtent().renderWidth(), traceExtent().renderHeight(), output,
                presentationResources().exposure().image(), presentationResources().postColorA(),
                presentationResources().postColorB(), ui);
    }

    private void writeFrameRoots(ByteBuffer roots, VulkanDeviceAddress worldPushAddress, FrameSnapshot snapshot,
                                 GpuBuffer pathScratch) {
        ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
        int base = roots.position();
        target.putLong(base + RtBindings.WORLD_PUSH_ADDRESS_OFFSET, worldPushAddress.value());
        target.putLong(base + RtBindings.WORLD_PATH_QUEUE_ADDRESS_OFFSET,
                pathScratch.deviceAddress().value());
        target.putFloat(base + RtBindings.WORLD_RECONSTRUCTION_MICRO_JITTER_SCALE_OFFSET,
                currentFrame.route() == DenoiserRoute.RAY_RECONSTRUCTION ? 0.1f : 0.0f);
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
                reconstruction.settings().signalEncoding() == DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
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
        gpuTiming.close();
        reconstruction.close();
        presenter.invalidateRenderedFrame();
        frameResources.destroy();
        history.reset();
        loggedActive = false;
        frameSnapshot = null;
        currentFrame = null;
        currentTrace = null;
        pendingGraphicsUse = null;
    }

    private static VkImageCopy2.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy2.Buffer region = VkImageCopy2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

}
