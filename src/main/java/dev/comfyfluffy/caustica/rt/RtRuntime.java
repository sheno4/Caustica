package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.slang.SlangRuntime;

/** Owns the live RT session and publishes one immutable rendering mode for each frame. */
public final class RtRuntime {
    public static final RtRuntime INSTANCE = new RtRuntime();

    public enum State {
        OFF,
        STARTING,
        ACTIVE,
        STOPPING,
        FAILED
    }

    private State state = State.OFF;
    private Session session;
    private boolean frameActive;
    private RtRuntimeHost host;

    private RtRuntime() {
    }

    public void installHost(RtRuntimeHost installedHost) {
        host = installedHost;
    }

    public static RtRuntimeHost host() {
        RtRuntimeHost installedHost = INSTANCE.host;
        if (installedHost == null) {
            throw new IllegalStateException("RT runtime host is not installed");
        }
        return installedHost;
    }

    /** Reconcile the requested mode and advance session startup at the client-tick boundary. */
    public void tick(SceneResources sceneResources, boolean startupSceneReady, long sceneId,
                     int displayWidth, int displayHeight, Runnable reconfigureSurface) {
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
        boolean sessionReady = session.tick(sceneResources, sceneId, displayWidth, displayHeight, starting);
        if (RtComposite.INSTANCE.hasFailed()) {
            fail(reconfigureSurface);
            return;
        }
        if (!sessionReady) {
            return;
        }
        if (!starting) {
            return;
        }
        if (!startupSceneReady || !RtComposite.INSTANCE.completeStartupBoundary()) {
            return;
        }

        state = State.ACTIVE;
        RtComposite.INSTANCE.resetExposureHistory();
        RtComposite.INSTANCE.resetFailureLatch();
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
                session.close();
            }
        } finally {
            session = null;
            try {
                GpuContext context = GpuContext.currentOrNull();
                if (context != null) {
                    context.destroy();
                }
            } finally {
                try {
                    NgxRuntime.INSTANCE.shutdown();
                } finally {
                    SlangRuntime.INSTANCE.shutdown();
                    state = State.OFF;
                }
            }
        }
    }

    public State state() {
        return state;
    }

    /**
     * True after startup has completed, including setup work immediately before the next render frame.
     *
     * <p>Stable across a whole frame: the state only advances on the client tick, which runs before both
     * {@code GameRenderer.extract} and {@code GameRenderer.render}. The extract-side and render-side hooks
     * that must agree on who owns terrain therefore never disagree within one frame.</p>
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
        if (GpuContext.backendOrNull() == null || !GpuContext.backendOrNull().rayTracingProvisioned()) {
            state = State.FAILED;
            CausticaMod.LOGGER.warn("RT runtime unavailable: the Vulkan device was not provisioned for ray tracing");
            return;
        }
        SlangRuntime.INSTANCE.resume();
        ProviderManager.INSTANCE.beginSession();
        session = new Session();
        state = State.STARTING;
        CausticaMod.LOGGER.info("RT runtime starting; source presentation remains active");
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
        try {
            reconfigureSurface.run();
            session.close();
        } finally {
            session = null;
            state = terminalState;
        }
    }

    private static final class Session {
        private GpuContext context;

        boolean tick(SceneResources sceneResources, long sceneId, int displayWidth, int displayHeight,
                     boolean starting) {
            if (context == null) {
                context = GpuContext.get();
                if (context == null) {
                    return false;
                }
            }

            boolean resourcesReady = RtComposite.INSTANCE.ensureResourcesReady(context, sceneResources);
            if (resourcesReady) {
                RtFrameStats.FRAME.beginIfInactive();
                ProviderManager.INSTANCE.updateScenes();
            }
            if (!sceneResources.sceneReady()) {
                return false;
            }
            if (starting && (displayWidth <= 0 || displayHeight <= 0
                    || !RtComposite.INSTANCE.ensurePresentationResourcesReady(
                    context, sceneId, displayWidth, displayHeight))) {
                return false;
            }
            if (CausticaConfig.Rt.Fg.ENABLED.value()) {
                RtDlssFg.INSTANCE.probeAvailabilityOnce();
            }
            return resourcesReady;
        }

        void close() {
            host().resetFrameBridge();
            ProviderManager.INSTANCE.stopProviders();
            RtWorkerPool.INSTANCE.shutdown();
            SlangRuntime.INSTANCE.requestShutdownWhenIdle();
            if (context == null) {
                ProviderManager.INSTANCE.shutdownResources();
                host().destroyUiPresentation();
                return;
            }

            // Scene producers and CPU workers are stopped. Drain the shared per-device submitter and wait
            // every queue before the remaining providers or runtime owners free session GPU resources.
            context.gpuExecutor().drainAndWaitIdle();
            ProviderManager.INSTANCE.shutdownResources();
            host().destroyUiPresentation();
            RtComposite.INSTANCE.destroy();
            RtEntityTextures.INSTANCE.reset();
            RtDlssFg.INSTANCE.destroy();
            RtFramePresenter.INSTANCE.destroy(context.vk());
            RtReflex.INSTANCE.destroy(context.vk());
            // GpuContext owns per-device infrastructure and survives RT sessions. Client shutdown destroys it.
            context = null;
        }
    }
}
