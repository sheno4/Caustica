package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftEngineWorldSession;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.pass.RtPassSchedulerBackend;
import dev.comfyfluffy.caustica.rt.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.slang.SlangRuntime;

import java.io.IOException;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

/** Owns the live RT session and publishes one immutable rendering mode for each frame. */
public final class RtRuntime {
    public static final RtRuntime INSTANCE = new RtRuntime();

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
        RENDERER_FAILED,
        RESOURCE_TRANSITION
    }

    private State state = State.OFF;
    private Session session;
    private boolean frameActive;
    private RuntimeHost host;
    private RenderSessionHost apiHost;
    private Path shaderCacheRoot;
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

    private RtRuntime() {
    }

    /** Renderer telemetry integration backed by renderer-owned collectors and sinks. */
    public RtTelemetry telemetry() {
        return RtTelemetryImpl.INSTANCE;
    }

    public void installVulkanBackend(VulkanRendererBackend backend) {
        GpuContext.installBackend(backend);
    }

    public void installHost(RuntimeHost installedHost) {
        host = installedHost;
    }

    /** Install process-scoped extension factories before the first renderer session opens. */
    public void installApiHost(RenderSessionHost installedHost) {
        apiHost = java.util.Objects.requireNonNull(installedHost, "installedHost");
    }

    /** Process-scoped extension host used when the renderer creates its engine session services. */
    public RenderSessionHost apiHost() {
        return java.util.Objects.requireNonNull(apiHost, "Caustica API host is not installed");
    }

    /** Configure the process shader cache before the first runtime activation. */
    public void configureShaderCache(Path cacheRoot) {
        shaderCacheRoot = java.util.Objects.requireNonNull(cacheRoot, "cacheRoot")
                .toAbsolutePath().normalize();
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

    /** Route host-side capture to the current scoped render pass when it exists. */
    /** Monotonic index of RT composite attempts across runtime activations. */
    public static long frameCounter() {
        return RtFrameRenderer.frameCounter();
    }

    public boolean rendererFailed() {
        return session != null && session.failed();
    }

    public String exposureSummary() {
        RtExposure exposure = session != null && session.renderer != null ? session.renderer.exposure() : null;
        return exposure != null && exposure.ready() ? exposure.debugSummaryLine() : null;
    }

    public boolean exportLatestResidualExposureExr(Path outputPath) throws IOException {
        return session != null && session.renderer != null
                && session.renderer.exportLatestResidualExposureExr(outputPath);
    }

    public boolean requiresSourceWorldFallback() {
        return session == null || session.requiresSourceFallback();
    }

    public void resetExposureHistory() {
        if (session != null && session.renderer != null) session.renderer.resetExposureHistory();
    }

    public void resetRendererFailure() {
        if (session != null && session.renderer != null) session.renderer.resetFailureLatch();
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

    public boolean presentHdr(GraphicsSubmission submission, long swapchainImage, int width, int height,
            long acquireSemaphore, long presentSemaphore, UiPresentationResources ui) {
        if (session == null || !session.presenter.isHdrPresentActive()) return false;
        session.presenter.presentHdr(submission, swapchainImage, width, height, acquireSemaphore, presentSemaphore, ui);
        return true;
    }

    public boolean presentSdrToPq(GraphicsSubmission submission, long swapchainImage, int width, int height,
            dev.comfyfluffy.caustica.api.gpu.GpuImage source,
            long acquireSemaphore, long presentSemaphore) {
        return session != null && session.presenter.presentSdrToPq(submission, swapchainImage, width, height,
                source, acquireSemaphore, presentSemaphore);
    }

    public boolean frameGenerationActive(boolean sceneAvailable) {
        return session != null && session.presenter.isActive(sceneAvailable);
    }

    public long hdrBackbufferView() {
        return session != null ? session.presenter.hdrBackbufferView() : 0L;
    }

    public long hdrBackbufferImage() {
        return session != null ? session.presenter.hdrBackbufferImage() : 0L;
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, VkDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapWidth, int swapHeight,
            long backbufferView, long sourceImage,
            boolean hdrBackbuffer, UiPresentationResources ui) {
        if (session != null) {
            session.presenter.prepareGeneratedFrame(submission, device, swapchain, swapchainImages, presentSemaphores,
                    swapWidth, swapHeight, backbufferView, sourceImage,
                    hdrBackbuffer, ui);
        }
    }

    public void flushGeneratedPresent(long swapchain, VkQueue presentQueue) {
        if (session != null) session.presenter.flushPendingPresent(swapchain, presentQueue);
    }

    public void captureHudless(long sourceImage, int width, int height, UiPresentationResources ui) {
        if (session != null) session.presenter.captureHudless(sourceImage, width, height, ui);
    }

    public static RuntimeHost host() {
        RuntimeHost installedHost = INSTANCE.host;
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
        boolean sessionReady;
        try {
            sessionReady = session.tick(sceneResources, worldEpoch, dimension,
                    displayWidth, displayHeight, starting);
        } catch (Throwable failure) {
            CausticaMod.LOGGER.error("RT runtime scene work failed; source presentation remains active", failure);
            fail(reconfigureSurface);
            return;
        }
        if (session.failed()) {
            fail(reconfigureSurface);
            return;
        }
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
        session.renderer.resetFailureLatch();
        host().resetPresentationFailure();
        if (CausticaConfig.Rt.Hdr.ENABLED.value()) {
            reconfigureSurface.run();
        }
        CausticaMod.LOGGER.info("RT runtime active");
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
                GpuContext context = GpuContext.currentOrNull();
                if (context != null) {
                    try {
                        lifecycle.closeDevice(context);
                    } finally {
                        context.destroy();
                    }
                }
            } finally {
                try {
                    lifecycle.stopProcess();
                } finally {
                    try {
                        NgxRuntime.INSTANCE.shutdown();
                    } finally {
                        try {
                            SlangRuntime.INSTANCE.shutdown();
                        } finally {
                            state = State.OFF;
                        }
                    }
                }
            }
        }
    }

    State state() {
        return state;
    }

    public WorldReplacement worldReplacement() {
        boolean failed = rendererFailed();
        if (!frameActive) {
            return WorldReplacement.FRAME_INACTIVE;
        } else if (failed) {
            return WorldReplacement.RENDERER_FAILED;
        } else if (GpuContext.currentOrNull() == null) {
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
    public static boolean active() {
        return INSTANCE.state == State.ACTIVE;
    }

    /** True only for hooks participating in the current, already-latched render frame. */
    public static boolean frameActive() {
        return INSTANCE.frameActive;
    }

    /** True while a session exists, including source-rendered startup. */
    public static boolean hasSession() {
        return INSTANCE.session != null;
    }

    /** PQ belongs to an active RT session; Off and Starting use the host's native SDR swapchain. */
    public static boolean wantsPqSwapchain() {
        return active() && CausticaConfig.Rt.Hdr.ENABLED.value();
    }

    private void start() {
        VulkanRendererBackend backend = GpuContext.backendOrNull();
        if (backend == null || !backend.capabilities().rayTracing()) {
            state = State.FAILED;
            CausticaMod.LOGGER.warn("RT runtime unavailable: the Vulkan device was not provisioned for ray tracing");
            return;
        }
        RtLifecycleCoordinator.RenderSessionEpoch epoch = lifecycle.beginRenderSession();
        startRuntimeActivation(epoch);
    }

    private void startRuntimeActivation(RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch) {
        RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch = lifecycle.beginRuntimeActivation();
        try {
            SlangRuntime.INSTANCE.resume();
            session = new Session(renderSessionEpoch, activationEpoch,
                    new RtFramePresenter(), java.util.Objects.requireNonNull(
                    shaderCacheRoot, "shader cache is not configured"));
            state = State.STARTING;
            CausticaMod.LOGGER.info("RT runtime starting; source presentation remains active");
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
                CausticaMod.LOGGER.error("RT runtime could not create its render session", failure);
            }
        }
    }

    /**
     * Hand world rendering back to the source renderer. Its visibility state and resources remain live
     * throughout the RT session, so no rebuild or wait is required.
     */
    private void stop(Runnable reconfigureSurface) {
        closeSession(reconfigureSurface, State.OFF);
        CausticaMod.LOGGER.info("RT runtime off; source presentation restored");
    }

    private void fail(Runnable reconfigureSurface) {
        closeSession(reconfigureSurface, State.FAILED);
        CausticaMod.LOGGER.warn("RT runtime startup failed; source presentation remains active");
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

    private static final class Session {
        private final RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch;
        private final RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch;
        private final RtFramePresenter presenter;
        private final Path shaderCacheRoot;
        private GpuContext context;
        private RtProgramBackend programs;
        private RtRetainedSceneBackend scenes;
        private RtPassSchedulerBackend passes;
        private MinecraftEngineWorldSession world;
        private RtFrameRenderer renderer;
        private long worldEpoch;

        private Session(RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch,
                        RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch,
                        RtFramePresenter presenter, Path shaderCacheRoot) {
            this.renderSessionEpoch = renderSessionEpoch;
            this.activationEpoch = activationEpoch;
            this.presenter = presenter;
            this.shaderCacheRoot = shaderCacheRoot;
        }

        private boolean failed() {
            return renderer != null && renderer.hasFailed();
        }

        private boolean requiresSourceFallback() {
            return renderer == null || renderer.requiresSourceWorldFallback();
        }

        boolean tick(SceneResources sceneResources, long requestedWorldEpoch,
                     MinecraftDimensionKey dimension, int displayWidth, int displayHeight,
                     boolean starting) {
            if (context == null) {
                context = GpuContext.get();
                if (context == null) {
                    return false;
                }
                INSTANCE.lifecycle.observeDevice(context);
            }
            RtLifecycleCoordinator.ResourcePackEpoch applied = INSTANCE.lifecycle.resourcePackEpoch();
            if (requestedWorldEpoch == 0L || dimension == null || applied == null) {
                closeWorld();
                return false;
            }
            if (world == null || worldEpoch != requestedWorldEpoch) {
                closeWorld();
                openWorld(requestedWorldEpoch, dimension, new ResourcePackEpoch(applied.generation()));
            }

            world.progress();
            scenes.progress();
            boolean resourcesReady = programs.active() != null;
            if (resourcesReady) {
                RtFrameStats.FRAME.beginIfInactive();
            }
            if (!sceneResources.sceneReady()) {
                return false;
            }
            if (starting && (displayWidth <= 0 || displayHeight <= 0
                    || !renderer.ensurePresentationResourcesReady(
                    context, requestedWorldEpoch, displayWidth, displayHeight))) {
                return false;
            }
            if (CausticaConfig.Rt.Fg.ENABLED.value()) {
                RtDlssFg.INSTANCE.probeAvailabilityOnce();
            }
            return resourcesReady;
        }

        private void openWorld(long epoch, MinecraftDimensionKey dimension,
                               ResourcePackEpoch resourcePackEpoch) {
            programs = new RtProgramBackend(context, shaderCacheRoot);
            scenes = new RtRetainedSceneBackend(context);
            passes = new RtPassSchedulerBackend(context,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM);
            try {
                world = new MinecraftEngineWorldSession(INSTANCE.apiHost(),
                        MinecraftApiBootstrap.minecraftSessionHost(), context,
                        programs, scenes, passes, dimension, resourcePackEpoch,
                        failure -> CausticaMod.LOGGER.error("Engine world-session failure", failure));
                renderer = new RtFrameRenderer(programs, scenes, passes,
                        world.services(), world.rootScene(), presenter);
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
            CausticaMod.LOGGER.warn("Resource-pack reload failed; keeping the active engine epoch", failure);
        }

        private void closeWorld() {
            if (world == null && programs == null && scenes == null && renderer == null) return;
            host().resetFrameBridge();
            if (world != null) {
                world.close();
                world = null;
            }
            if (context != null) context.gpuExecutor().drainAndWaitIdle();
            if (renderer != null) {
                renderer.destroy();
                renderer = null;
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
                RtDlssFg.INSTANCE.destroy();
                presenter.destroy(context.vk());
                context.backend().lowLatency().destroy(context.vk());
            }
            context = null;
        }
    }
}
