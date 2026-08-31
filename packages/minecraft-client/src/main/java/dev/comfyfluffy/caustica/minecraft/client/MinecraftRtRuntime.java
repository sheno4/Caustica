package dev.comfyfluffy.caustica.minecraft.client;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftEngineWorldSession;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssFrameGeneration;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssSuperResolution;
import dev.comfyfluffy.caustica.nvidia.nrd.NrdBackendFactory;
import dev.comfyfluffy.caustica.nvidia.nrd.NrdDevice;
import dev.comfyfluffy.caustica.nvidia.nrd.NrdLibrary;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.runtime.RtDenoisingSettings;
import dev.comfyfluffy.caustica.renderer.runtime.RtFrameRenderer;
import dev.comfyfluffy.caustica.renderer.runtime.RtDlssSuperResolution;
import dev.comfyfluffy.caustica.renderer.runtime.RtLifecycleCoordinator;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import dev.comfyfluffy.caustica.renderer.runtime.pass.RtPassSchedulerBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.AcquiredSwapchainTarget;
import dev.comfyfluffy.caustica.renderer.presentation.BorrowedImage;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationSwapchain;
import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;
import dev.comfyfluffy.caustica.slang.SlangRuntime;

import java.io.IOException;
import java.nio.file.Path;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the live RT session and publishes one immutable rendering mode for each frame. */
public final class MinecraftRtRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftRtRuntime.class);
    enum State {
        OFF,
        STARTING,
        ACTIVE,
        STOPPING,
        FAILED
    }

    public enum WorldReplacement {
        READY,
        FRAME_INACTIVE,
        DEVICE_UNAVAILABLE,
        RESOURCE_TRANSITION
    }

    private State state = State.OFF;
    private Session session;
    private boolean frameActive;
    private RuntimeHost host;
    private final RenderSessionHost apiHost;
    private final MinecraftWorldSessionHost minecraftSessionHost;
    private final Path shaderCacheRoot;
    private final SlangRuntime slangRuntime;
    private final RtTelemetry telemetry;
    private final NgxRuntime.Settings ngxSettings;
    private VulkanRendererBackend vulkanBackend;
    private VulkanDeviceContext vulkanContext;
    private NgxRuntime ngxRuntime;
    private NrdLibrary nrdLibrary;
    private DenoiserBackendFactory denoiserFactory;
    private final RtLifecycleCoordinator lifecycle = new RtLifecycleCoordinator(
            new RtLifecycleCoordinator.Listener() {
                @Override
                public void resourcePackReloadStarting(RtLifecycleCoordinator.ResourcePackEpoch pending) {
                    if (session != null) {
                        session.resourceReloadStarting();
                    }
                }

                @Override
                public void resourcePackApplied(RtLifecycleCoordinator.ResourcePackEpoch epoch) {
                    if (session != null) {
                        session.resourcePackApplied(new ResourcePackEpoch(epoch.generation()));
                    }
                }

                @Override
                public void resourcePackReloadFailed(RtLifecycleCoordinator.ResourcePackEpoch pending,
                                                     Throwable failure) {
                    if (session != null) {
                        session.resourceReloadFailed(failure);
                    }
                }

            });

    public MinecraftRtRuntime(RenderSessionHost apiHost, MinecraftWorldSessionHost minecraftSessionHost,
                     SlangRuntime slangRuntime, Path shaderCacheRoot, RtTelemetry telemetry,
                     NgxRuntime.Settings ngxSettings) {
        this.apiHost = java.util.Objects.requireNonNull(apiHost, "apiHost");
        this.minecraftSessionHost = java.util.Objects.requireNonNull(minecraftSessionHost, "minecraftSessionHost");
        this.slangRuntime = java.util.Objects.requireNonNull(slangRuntime, "slangRuntime");
        this.shaderCacheRoot = java.util.Objects.requireNonNull(shaderCacheRoot, "shaderCacheRoot")
                .toAbsolutePath().normalize();
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.ngxSettings = java.util.Objects.requireNonNull(ngxSettings, "ngxSettings");
    }

    /** Renderer telemetry integration backed by renderer-owned collectors and sinks. */
    public RtTelemetry telemetry() {
        return telemetry;
    }

    /** Attach the host backend whose device lifetime encloses every RT activation. */
    public synchronized void installVulkanBackend(VulkanRendererBackend backend) {
        VulkanRendererBackend installed = java.util.Objects.requireNonNull(backend, "backend");
        if (vulkanContext != null && vulkanContext.backend() != installed) {
            throw new IllegalStateException("Cannot replace a Vulkan backend while its device context is live");
        }
        vulkanBackend = installed;
    }

    /** The device context already created for this runtime, without starting device work. */
    public synchronized VulkanDeviceContext vulkanContextOrNull() {
        return vulkanContext;
    }

    /** Create or return the single device context owned by this runtime. */
    public synchronized VulkanDeviceContext requireVulkanContext() {
        if (vulkanContext != null) {
            return vulkanContext;
        }
        VulkanRendererBackend backend = java.util.Objects.requireNonNull(
                vulkanBackend, "Vulkan renderer backend is not installed");
        VulkanDeviceContext created = VulkanDeviceContext.create(backend);
        NgxRuntime createdNgxRuntime = null;
        DenoiserBackendFactory createdDenoiserFactory = null;
        try {
            lifecycle.observeDevice(created);
            createdNgxRuntime = new NgxRuntime(created, ngxSettings);
            NrdLibrary createdNrdLibrary = NrdLibrary.loadBundled(
                    shaderCacheRoot.getParent().resolve("natives"));
            var physicalDevice = backend.device().getPhysicalDevice();
            createdDenoiserFactory = NrdBackendFactory.open(createdNrdLibrary, new NrdDevice(
                    physicalDevice.getInstance().address(), physicalDevice.address(),
                    backend.device().address(), backend.graphicsQueue().familyIndex(),
                    VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT));
            vulkanContext = created;
            ngxRuntime = createdNgxRuntime;
            nrdLibrary = createdNrdLibrary;
            denoiserFactory = createdDenoiserFactory;
            return created;
        } catch (Throwable failure) {
            try {
                if (createdDenoiserFactory != null) createdDenoiserFactory.close();
                if (createdNgxRuntime != null) createdNgxRuntime.shutdown();
            } finally {
                try {
                    lifecycle.closeDevice(created);
                } finally {
                    created.destroy();
                }
            }
            throw failure;
        }
    }

    private NgxRuntime requireNgxRuntime() {
        requireVulkanContext();
        return java.util.Objects.requireNonNull(ngxRuntime, "NGX runtime was not created with the Vulkan device");
    }

    private DlssRayReconstruction.Settings rayReconstructionSettings(RtDenoisingSettings denoising) {
        return new DlssRayReconstruction.Settings(
                denoising.route() == DenoiserRoute.RAY_RECONSTRUCTION,
                CausticaConfig.Rt.DlssRr.QUALITY.value(), CausticaConfig.Rt.DlssRr.PRESET.value());
    }

    private DlssSuperResolution.Settings superResolutionSettings(RtDenoisingSettings denoising) {
        return superResolutionSettings(denoising,
                CausticaConfig.Rt.DlssSr.QUALITY.value(), CausticaConfig.Rt.DlssSr.PRESET.value());
    }

    static DlssSuperResolution.Settings superResolutionSettings(
            RtDenoisingSettings denoising, int quality, int preset) {
        return new DlssSuperResolution.Settings(
                denoising.route() == DenoiserRoute.TEMPORAL_DENOISER,
                quality, preset);
    }

    private RtDenoisingSettings denoisingSettings() {
        return denoisingSettings(CausticaConfig.Rt.Denoising.ROUTE.get(),
                CausticaConfig.Rt.Denoising.METHOD.get());
    }

    static RtDenoisingSettings denoisingSettings(String routeValue, String methodValue) {
        DenoiserRoute route = switch (routeValue) {
            case "raw" -> DenoiserRoute.RAW;
            case "temporal_denoiser" -> DenoiserRoute.TEMPORAL_DENOISER;
            case "ray_reconstruction" -> DenoiserRoute.RAY_RECONSTRUCTION;
            default -> throw new IllegalStateException("unsupported denoising route");
        };
        DenoiserSignalEncoding encoding = route == DenoiserRoute.TEMPORAL_DENOISER
                && methodValue.equals("reblur")
                ? DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
                : DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE;
        return new RtDenoisingSettings(route, encoding);
    }

    private DenoiserBackendFactory requireDenoiserFactory() {
        requireVulkanContext();
        return java.util.Objects.requireNonNull(denoiserFactory,
                "denoiser factory was not created with the Vulkan device");
    }

    private DlssFrameGeneration.Settings frameGenerationSettings() {
        return new DlssFrameGeneration.Settings(CausticaConfig.Rt.Fg.ENABLED.value());
    }

    private RtFramePresenter.Settings presentationSettings() {
        return new RtFramePresenter.Settings(CausticaConfig.Rt.Hdr.enabled(),
                CausticaConfig.Rt.Hdr.swapchainPqActive(), CausticaConfig.Rt.Hdr.uiNits());
    }

    public void installHost(RuntimeHost installedHost) {
        host = installedHost;
    }

    /** Process-scoped extension host used when the renderer creates its engine session services. */
    public RenderSessionHost apiHost() {
        return java.util.Objects.requireNonNull(apiHost, "Caustica API host is not installed");
    }

    /** Start process-scoped lifecycle tracking after the host has installed its extensions and options. */
    public void startProcess() {
        lifecycle.startProcess();
    }

    /** Observe a resource pack that is available to the client, including the initial title-screen pack. */
    public void observeResourcePackAvailable() {
        lifecycle.observeResourcePackAvailable();
    }

    /** Begin the host-visible half of a resource-pack reload before its old images are destroyed. */
    public long beginResourcePackReload() {
        return lifecycle.beginResourcePackReload().generation();
    }

    /** Attach a manual-reload future; its result is applied on the next client tick. */
    public void trackResourcePackReload(long generation,
                                        java.util.concurrent.CompletableFuture<?> future) {
        lifecycle.trackResourcePackReload(generation, future);
    }

    /** Whether a completed resource pack can be replayed into newly created session instances. */
    public boolean hasAppliedResourcePack() {
        return lifecycle.resourcePackEpoch() != null && lifecycle.pendingResourcePackEpoch() == null;
    }

    /** Monotonic index of RT composite attempts in the current renderer instance. */
    public long frameCounter() {
        return session != null && session.renderer != null ? session.renderer.frameCounter() : 0L;
    }

    public String exposureSummary() {
        RtExposure exposure = session != null && session.renderer != null ? session.renderer.exposure() : null;
        return exposure != null && exposure.ready() ? exposure.debugSummaryLine() : null;
    }

    public boolean exportLatestResidualExposureExr(Path outputPath) throws IOException {
        return frameActive && session != null && session.renderer != null
                && session.renderer.exportLatestResidualExposureExr(outputPath);
    }

    public boolean requiresSourceWorldFallback() {
        return session == null || session.requiresSourceFallback();
    }

    public void resetExposureHistory() {
        if (session != null && session.renderer != null) session.renderer.resetExposureHistory();
    }

    public void captureFrame(FrameSnapshot snapshot) {
        if (session != null && session.renderer != null) session.renderer.captureFrame(snapshot);
    }

    public void beginFrame() {
        if (frameActive && session != null && session.renderer != null) session.renderer.beginFrame();
    }

    public void recordUiPasses(org.lwjgl.vulkan.VkCommandBuffer commandBuffer, GpuImage uiLayer) {
        if (session != null && session.renderer != null) session.renderer.recordUiPasses(commandBuffer, uiLayer);
    }

    public void finishGraphicsUse() {
        if (session != null && session.renderer != null) session.renderer.finishGraphicsUse();
    }

    public void endFrame() {
        if (session != null && session.renderer != null) session.renderer.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        return session != null && session.renderer != null
                && session.renderer.composite(nativeColorImage, width, height);
    }

    public boolean isHdrPresentActive() {
        return session != null && session.presenter.isHdrPresentActive();
    }

    public boolean isPqSdrPresentActive() {
        return session != null && session.presenter.isPqSdrPresentActive();
    }

    public boolean presentHdr(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            UiPresentationResources ui) {
        if (session == null || !session.presenter.isHdrPresentActive()) return false;
        session.presenter.presentHdr(submission, target, ui);
        return true;
    }

    public boolean presentSdrToPq(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            dev.comfyfluffy.caustica.api.vulkan.GpuImage source) {
        return session != null && session.presenter.presentSdrToPq(submission, target, source);
    }

    public boolean frameGenerationActive(boolean sceneAvailable) {
        return frameActive && session != null && session.presenter.isActive(sceneAvailable);
    }

    public dev.comfyfluffy.caustica.api.vulkan.GpuImage hdrBackbuffer() {
        return session != null ? session.presenter.hdrBackbuffer() : null;
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, PresentationSwapchain swapchain,
            BorrowedImage source, boolean hdrBackbuffer, UiPresentationResources ui) {
        if (session != null) {
            session.presenter.prepareGeneratedFrame(submission, swapchain, source, hdrBackbuffer, ui);
        }
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, PresentationSwapchain swapchain,
            dev.comfyfluffy.caustica.api.vulkan.GpuImage source,
            boolean hdrBackbuffer, UiPresentationResources ui) {
        if (session != null) {
            session.presenter.prepareGeneratedFrame(submission, swapchain, source, hdrBackbuffer, ui);
        }
    }

    public void flushGeneratedPresent(PresentationSwapchain swapchain, VkQueue presentQueue) {
        if (session != null) session.presenter.flushPendingPresent(swapchain, presentQueue);
    }

    public void captureHudless(BorrowedImage source, UiPresentationResources ui) {
        if (session != null) session.presenter.captureHudless(source, ui);
    }

    public RuntimeHost host() {
        RuntimeHost installedHost = host;
        if (installedHost == null) {
            throw new IllegalStateException("RT runtime host is not installed");
        }
        return installedHost;
    }

    /** Reconcile the requested mode and advance session startup at the client-tick boundary. */
    public void tick(SceneResources sceneResources, boolean startupSceneReady, long worldEpoch,
                     MinecraftDimensionKey dimension,
                     int displayWidth, int displayHeight, Runnable reconfigureSurface) {
        lifecycle.drainResourcePackCompletions();
        boolean requested = CausticaConfig.Rt.ENABLED.value();
        if (!requested) {
            if (state == State.STARTING || state == State.ACTIVE) {
                stop(reconfigureSurface);
            } else if (state == State.FAILED) {
                state = State.OFF;
            }
            return;
        }

        if (state == State.OFF) {
            start();
        }
        if (state != State.STARTING && state != State.ACTIVE) {
            return;
        }

        boolean starting = state == State.STARTING;
        // Scene work failing is a renderer defect, not a condition to present around: let it surface.
        boolean sessionReady = session.tick(sceneResources, worldEpoch, dimension,
                displayWidth, displayHeight, starting);
        if (!sessionReady) {
            return;
        }
        if (!starting) {
            return;
        }
        if (!startupSceneReady || !session.renderer.completeStartupBoundary()) {
            return;
        }

        state = State.ACTIVE;
        session.renderer.resetExposureHistory();
        host().resetPresentationFailure();
        if (CausticaConfig.Rt.Hdr.ENABLED.value()) {
            reconfigureSurface.run();
        }
        LOGGER.info("RT runtime active");
    }

    /** Latch the session state consumed by every hook in this render frame. */
    public void beginRenderFrame() {
        frameActive = state == State.ACTIVE;
    }

    public void shutdown() {
        frameActive = false;
        try {
            if (session != null) {
                state = State.STOPPING;
                Session closing = session;
                session = null;
                try {
                    closeRuntimeActivation(closing);
                } finally {
                    lifecycle.closeRenderSession(closing.renderSessionEpoch);
                }
            }
        } finally {
            session = null;
            try {
                VulkanDeviceContext context = vulkanContext;
                NgxRuntime closingNgxRuntime = ngxRuntime;
                DenoiserBackendFactory closingDenoiserFactory = denoiserFactory;
                vulkanContext = null;
                ngxRuntime = null;
                denoiserFactory = null;
                nrdLibrary = null;
                if (context != null) {
                    try {
                        context.waitIdle();
                        try {
                            if (closingDenoiserFactory != null) closingDenoiserFactory.close();
                        } finally {
                            if (closingNgxRuntime != null) closingNgxRuntime.shutdown();
                        }
                    } finally {
                        try {
                            lifecycle.closeDevice(context);
                        } finally {
                            context.destroy();
                        }
                    }
                }
            } finally {
                try {
                    lifecycle.stopProcess();
                } finally {
                    try {
                        SlangRuntime compilerRuntime = slangRuntime;
                        if (compilerRuntime != null) compilerRuntime.shutdown();
                    } finally {
                        vulkanBackend = null;
                        state = State.OFF;
                    }
                }
            }
        }
    }

    State state() {
        return state;
    }

    public WorldReplacement worldReplacement() {
        if (!frameActive) {
            return WorldReplacement.FRAME_INACTIVE;
        } else if (vulkanContext == null) {
            return WorldReplacement.DEVICE_UNAVAILABLE;
        } else if (requiresSourceWorldFallback()) {
            return WorldReplacement.RESOURCE_TRANSITION;
        }
        return WorldReplacement.READY;
    }

    /**
     * True after startup has completed, including setup work immediately before the next render frame.
     *
     * <p>Stable across a whole frame: the state only advances on the client tick, which runs before both
     * {@code GameRenderer.extract} and {@code GameRenderer.render}. The extract-side and render-side hooks
     * that must agree on scene ownership therefore never disagree within one frame.</p>
     */
    public boolean active() {
        return state == State.ACTIVE;
    }

    /** True only for hooks participating in the current, already-latched render frame. */
    public boolean frameActive() {
        return frameActive;
    }

    /** True while a session exists, including source-rendered startup. */
    public boolean hasSession() {
        return session != null;
    }

    /** PQ belongs to an active RT session; Off and Starting use the host's native SDR swapchain. */
    public boolean wantsPqSwapchain() {
        return active() && CausticaConfig.Rt.Hdr.ENABLED.value();
    }

    private void start() {
        VulkanRendererBackend backend = vulkanBackend;
        if (backend == null || !backend.capabilities().rayTracing()) {
            state = State.FAILED;
            LOGGER.warn("RT runtime unavailable: the Vulkan device was not provisioned for ray tracing");
            return;
        }
        RtLifecycleCoordinator.RenderSessionEpoch epoch = lifecycle.beginRenderSession();
        startRuntimeActivation(epoch);
    }

    private void startRuntimeActivation(RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch) {
        RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch = lifecycle.beginRuntimeActivation();
        try {
            VulkanDeviceContext context = requireVulkanContext();
            DlssFrameGeneration frameGeneration = new DlssFrameGeneration(
                    requireNgxRuntime(), frameGenerationSettings());
            session = new Session(renderSessionEpoch, activationEpoch,
                    context, frameGeneration,
                    new RtFramePresenter(context, frameGeneration, this::presentationSettings),
                    java.util.Objects.requireNonNull(
                    shaderCacheRoot, "shader cache is not configured"));
            state = State.STARTING;
            LOGGER.info("RT runtime starting; source presentation remains active");
        } catch (Throwable failure) {
            try {
                if (session != null) {
                    session.closeActivation();
                    session = null;
                }
            } finally {
                lifecycle.closeRuntimeActivation(activationEpoch);
                lifecycle.closeRenderSession(renderSessionEpoch);
                state = State.FAILED;
                LOGGER.error("RT runtime could not create its render session", failure);
            }
        }
    }

    /**
     * Hand world rendering back to the source renderer. Its visibility state and resources remain live
     * throughout the RT session, so no rebuild or wait is required.
     */
    private void stop(Runnable reconfigureSurface) {
        closeSession(reconfigureSurface, State.OFF);
        LOGGER.info("RT runtime off; source presentation restored");
    }

    private void closeSession(Runnable reconfigureSurface, State terminalState) {
        state = State.STOPPING;
        frameActive = false;
        Session closing = session;
        session = null;
        try {
            reconfigureSurface.run();
        } finally {
            try {
                closeRuntimeActivation(closing);
            } finally {
                try {
                    lifecycle.closeRenderSession(closing.renderSessionEpoch);
                } finally {
                    state = terminalState;
                }
            }
        }
    }

    private void closeRuntimeActivation(Session closing) {
        try {
            lifecycle.closeRuntimeActivation(closing.activationEpoch);
        } finally {
            closing.closeActivation();
        }
    }

    private final class Session {
        private final RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch;
        private final RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch;
        private final DlssFrameGeneration frameGeneration;
        private final RtFramePresenter presenter;
        private final Path shaderCacheRoot;
        private VulkanDeviceContext context;
        private RtProgramBackend programs;
        private RtRetainedSceneBackend scenes;
        private RtPassSchedulerBackend passes;
        private DlssRayReconstruction rayReconstruction;
        private DlssSuperResolution superResolution;
        private MinecraftEngineWorldSession world;
        private RtFrameRenderer renderer;
        private long worldEpoch;

        private Session(RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch,
                        RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch,
                        VulkanDeviceContext context, DlssFrameGeneration frameGeneration,
                        RtFramePresenter presenter, Path shaderCacheRoot) {
            this.renderSessionEpoch = renderSessionEpoch;
            this.activationEpoch = activationEpoch;
            this.context = context;
            this.frameGeneration = frameGeneration;
            this.presenter = presenter;
            this.shaderCacheRoot = shaderCacheRoot;
        }

        private boolean requiresSourceFallback() {
            return renderer == null || renderer.requiresSourceWorldFallback();
        }

        boolean tick(SceneResources sceneResources, long requestedWorldEpoch,
                     MinecraftDimensionKey dimension, int displayWidth, int displayHeight,
                     boolean starting) {
            frameGeneration.configure(frameGenerationSettings());
            RtLifecycleCoordinator.ResourcePackEpoch applied = lifecycle.resourcePackEpoch();
            if (requestedWorldEpoch == 0L || dimension == null || applied == null) {
                closeWorld();
                return false;
            }
            if (world == null || worldEpoch != requestedWorldEpoch) {
                closeWorld();
                openWorld(requestedWorldEpoch, dimension, new ResourcePackEpoch(applied.generation()));
            }
            RtDenoisingSettings denoising = denoisingSettings();
            rayReconstruction.configure(rayReconstructionSettings(denoising));
            superResolution.configure(superResolutionSettings(denoising));
            renderer.configureDenoising(denoising);

            world.progress();
            boolean resourcesReady = programs.active() != null;
            if (resourcesReady) {
                telemetry.beginFrameIfInactive();
            }
            if (!sceneResources.sceneReady()) {
                return false;
            }
            if (starting && (displayWidth <= 0 || displayHeight <= 0
                    || !renderer.ensurePresentationResourcesReady(
                    requestedWorldEpoch, displayWidth, displayHeight))) {
                return false;
            }
            if (frameGeneration.enabled()) {
                frameGeneration.probeAvailabilityOnce();
            }
            return resourcesReady;
        }

        private void openWorld(long epoch, MinecraftDimensionKey dimension,
                               ResourcePackEpoch resourcePackEpoch) {
            programs = new RtProgramBackend(context,
                    slangRuntime,
                    shaderCacheRoot);
            scenes = new RtRetainedSceneBackend(context);
            passes = new RtPassSchedulerBackend(context,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM);
            RtDenoisingSettings denoising = denoisingSettings();
            rayReconstruction = new DlssRayReconstruction(
                    requireNgxRuntime(), rayReconstructionSettings(denoising));
            superResolution = new DlssSuperResolution(
                    requireNgxRuntime(), superResolutionSettings(denoising));
            try {
                world = new MinecraftEngineWorldSession(apiHost(),
                        minecraftSessionHost, context,
                        programs, scenes, passes, dimension, resourcePackEpoch,
                        failure -> LOGGER.error("Engine world-session failure", failure));
                renderer = new RtFrameRenderer(context, programs, scenes, passes,
                        world.services(), presenter, rayReconstruction,
                        new RtDlssSuperResolution(superResolution),
                        requireDenoiserFactory(), denoising, telemetry);
                worldEpoch = epoch;
            } catch (Throwable failure) {
                closeWorld();
                throw failure;
            }
        }

        private void resourcePackApplied(ResourcePackEpoch epoch) {
            if (world != null && epoch.generation() > world.resourcePackEpoch().generation()) {
                world.resourcePackChanged(epoch);
            }
        }

        private void resourceReloadStarting() {
            if (renderer != null) renderer.resetSceneHistory();
        }

        private void resourceReloadFailed(Throwable failure) {
            LOGGER.warn("Resource-pack reload failed; keeping the active engine epoch", failure);
        }

        private void closeWorld() {
            if (world == null && programs == null && scenes == null
                    && renderer == null && rayReconstruction == null && superResolution == null) return;
            host().resetFrameBridge();
            if (world != null) {
                world.close();
                world = null;
            }
            if (context != null) context.gpuExecutor().drainAndWaitIdle();
            if (renderer != null) {
                renderer.destroy();
                renderer = null;
                rayReconstruction = null;
                superResolution = null;
            } else {
                if (rayReconstruction != null) {
                    rayReconstruction.destroyAfterDeviceIdle();
                    rayReconstruction = null;
                }
                if (superResolution != null) {
                    superResolution.destroyAfterDeviceIdle();
                    superResolution = null;
                }
            }
            if (scenes != null) {
                scenes.shutdownAfterDeviceIdle();
                scenes = null;
            }
            if (programs != null) {
                programs.close();
                programs = null;
            }
            passes = null;
            worldEpoch = 0L;
        }

        void closeActivation() {
            closeWorld();
            host().destroyUiPresentation();
            if (context != null) {
                context.waitIdle();
                presenter.destroy(context.vk());
                context.backend().lowLatency().destroy(context.vk());
            }
            context = null;
        }
    }
}
