package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
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
    private final RtProgramManager programManager = new RtProgramManager();
    private final RtLifecycleCoordinator lifecycle = new RtLifecycleCoordinator(
            new RtLifecycleCoordinator.Listener() {
                @Override
                public void processStopping() {
                    programManager.shutdown();
                }

                @Override
                public void resourcePackReloadStarting(RtLifecycleCoordinator.ResourcePackEpoch pending) {
                    if (session != null) {
                        session.renderer.onResourceReloadStart();
                    }
                }

                @Override
                public void resourcePackApplied(RtLifecycleCoordinator.ResourcePackEpoch epoch) {
                    if (session != null) {
                        session.renderer.onResourcePackApplied();
                    }
                }

                @Override
                public void resourcePackReloadFailed(RtLifecycleCoordinator.ResourcePackEpoch pending,
                                                     Throwable failure) {
                    if (session != null) {
                        session.renderer.onResourceReloadFailed();
                        if (lifecycle.resourcePackEpoch() != null) {
                            session.renderer.onResourcePackApplied();
                        }
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

    /** Configure the process shader cache before the first runtime activation. */
    public void configureShaderCache(Path cacheRoot) {
        programManager.configureCacheRoot(cacheRoot);
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
    public <T extends CausticaRenderPass> T renderPass(ResourceId id, Class<T> type) {
        if (session == null) {
            return null;
        }
        CausticaRenderPass pass = session.contributions.renderPasses().get(id);
        return type.isInstance(pass) ? type.cast(pass) : null;
    }

    /** Monotonic index of RT composite attempts across runtime activations. */
    public static long frameCounter() {
        return RtFrameRenderer.frameCounter();
    }

    public boolean rendererFailed() {
        return session != null && session.renderer.hasFailed();
    }

    public String exposureSummary() {
        RtExposure exposure = session != null ? session.renderer.exposure() : null;
        return exposure != null && exposure.ready() ? exposure.debugSummaryLine() : null;
    }

    public boolean exportLatestResidualExposureExr(Path outputPath) throws IOException {
        return session != null && session.renderer.exportLatestResidualExposureExr(outputPath);
    }

    public boolean requiresSourceWorldFallback() {
        return session == null || session.renderer.requiresSourceWorldFallback();
    }

    public void resetExposureHistory() {
        if (session != null) session.renderer.resetExposureHistory();
    }

    public void resetRendererFailure() {
        if (session != null) session.renderer.resetFailureLatch();
    }

    public void captureFrame(FrameSnapshot snapshot) {
        if (session != null) session.renderer.captureFrame(snapshot);
    }

    public void beginFrame() {
        if (frameActive && session != null) session.renderer.beginFrame();
    }

    public void recordOverlayPasses() {
        if (session != null) session.renderer.recordOverlayPasses();
    }

    public void finishGraphicsUse() {
        if (session != null) session.renderer.finishGraphicsUse();
    }

    public void endFrame() {
        if (session != null) session.renderer.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        return session != null && session.renderer.composite(nativeColorImage, width, height);
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
            long sourceView, long acquireSemaphore, long presentSemaphore) {
        return session != null && session.presenter.presentSdrToPq(submission, swapchainImage, width, height,
                sourceView, acquireSemaphore, presentSemaphore);
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
    public void tick(SceneResources sceneResources, boolean startupSceneReady, long sceneId,
                     int displayWidth, int displayHeight, Runnable reconfigureSurface) {
        lifecycle.drainResourcePackCompletions();
        CausticaRegistry.Selection selection = CausticaApi.registry().selection();
        VulkanRendererBackend backend = GpuContext.backendOrNull();
        programManager.request(selection, backend != null && backend.capabilities().shaderExecutionReordering());
        RtProgramManager.Program candidate = programManager.candidate();
        boolean requested = CausticaConfig.Rt.ENABLED.value();
        if (requested && session != null && !session.matches(selection) && candidate != null) {
            rotateRuntimeActivation(reconfigureSurface);
        }
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
            sessionReady = session.tick(sceneResources, sceneId, displayWidth, displayHeight, starting);
        } catch (Throwable failure) {
            CausticaMod.LOGGER.error("RT runtime scene work failed; source presentation remains active", failure);
            fail(reconfigureSurface);
            return;
        }
        if (session.renderer.hasFailed()) {
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
        CausticaRegistry.RuntimeContributions contributions = null;
        ProviderManager providers = null;
        try {
            SlangRuntime.INSTANCE.resume();
            contributions = CausticaApi.registry().createRuntimeContributions();
            providers = new ProviderManager(contributions);
            RtFramePresenter presenter = new RtFramePresenter();
            RtFrameRenderer renderer = new RtFrameRenderer(programManager, providers, presenter, contributions);
            providers.bindSceneGeometry(renderer.sceneGeometry());
            session = new Session(renderSessionEpoch, activationEpoch, contributions, providers, renderer, presenter);
            if (hasAppliedResourcePack()) {
                renderer.onResourcePackApplied();
            }
            state = State.STARTING;
            CausticaMod.LOGGER.info("RT runtime starting; source presentation remains active");
        } catch (Throwable failure) {
            try {
                if (session != null) {
                    session.closeActivation();
                    session = null;
                } else if (contributions != null) {
                    disposeUnstartedContributions(contributions, providers);
                }
            } finally {
                lifecycle.closeRuntimeActivation(activationEpoch);
                lifecycle.closeRenderSession(renderSessionEpoch);
                state = State.FAILED;
                CausticaMod.LOGGER.error("RT runtime could not create its render session", failure);
            }
        }
    }

    private static void disposeUnstartedContributions(CausticaRegistry.RuntimeContributions contributions,
            ProviderManager providers) {
        ProviderManager closing = providers != null ? providers : new ProviderManager(contributions);
        closing.stopProviders();
        closing.shutdownResources();
        closing.endSession();
        for (CausticaRenderPass pass : contributions.renderPasses().values()) {
            pass.destroy();
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

    private void rotateRuntimeActivation(Runnable reconfigureSurface) {
        state = State.STOPPING;
        frameActive = false;
        Session closing = session;
        session = null;
        RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch = closing.renderSessionEpoch;
        try {
            reconfigureSurface.run();
        } finally {
            try {
                closeRuntimeActivation(closing);
                startRuntimeActivation(renderSessionEpoch);
            } catch (Throwable failure) {
                lifecycle.closeRenderSession(renderSessionEpoch);
                state = State.FAILED;
                CausticaMod.LOGGER.error("RT runtime could not replace its active runtime closure", failure);
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
        private final CausticaRegistry.RuntimeContributions contributions;
        private final ProviderManager providers;
        private final RtFrameRenderer renderer;
        private final RtFramePresenter presenter;
        private GpuContext context;

        private Session(RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch,
                        RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch,
                        CausticaRegistry.RuntimeContributions contributions, ProviderManager providers,
                        RtFrameRenderer renderer, RtFramePresenter presenter) {
            this.renderSessionEpoch = renderSessionEpoch;
            this.activationEpoch = activationEpoch;
            this.contributions = contributions;
            this.providers = providers;
            this.renderer = renderer;
            this.presenter = presenter;
        }

        private void consumeSceneResetRequest() {
            if (providers.consumeSceneResetRequest()) {
                renderer.resetSceneHistory();
            }
        }

        private boolean matches(CausticaRegistry.Selection selection) {
            for (var entry : selection.bindings().entrySet()) {
                if (!entry.getValue().feature().id().equals(contributions.selectedSlots().get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        boolean tick(SceneResources sceneResources, long sceneId, int displayWidth, int displayHeight,
                     boolean starting) {
            consumeSceneResetRequest();
            if (context == null) {
                context = GpuContext.get();
                if (context == null) {
                    return false;
                }
                INSTANCE.lifecycle.observeDevice(context);
            }

            boolean resourcesReady = renderer.ensureResourcesReady(context, sceneResources);
            if (resourcesReady) {
                RtFrameStats.FRAME.beginIfInactive();
                providers.updateScenes(context, SceneOrigin.ZERO);
                renderer.sceneGeometry().progress(context);
            }
            if (!sceneResources.sceneReady()) {
                return false;
            }
            if (starting && (displayWidth <= 0 || displayHeight <= 0
                    || !renderer.ensurePresentationResourcesReady(
                    context, sceneId, displayWidth, displayHeight))) {
                return false;
            }
            if (CausticaConfig.Rt.Fg.ENABLED.value()) {
                RtDlssFg.INSTANCE.probeAvailabilityOnce();
            }
            return resourcesReady;
        }

        void closeActivation() {
            host().resetFrameBridge();
            providers.stopProviders();
            if (context == null) {
                providers.shutdownResources();
                providers.endSession();
                host().destroyUiPresentation();
                return;
            }

            // Scene producers and CPU workers are stopped. Drain the shared per-device submitter and wait
            // every queue before the remaining providers or runtime owners free session GPU resources.
            context.gpuExecutor().drainAndWaitIdle();
            renderer.destroy();
            providers.shutdownResources();
            providers.endSession();
            host().destroyUiPresentation();
            RtDlssFg.INSTANCE.destroy();
            presenter.destroy(context.vk());
            context.backend().lowLatency().destroy(context.vk());
            // GpuContext owns per-device infrastructure and survives RT sessions. Client shutdown destroys it.
            context = null;
        }
    }
}
