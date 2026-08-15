package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
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
    private final RtLifecycleCoordinator lifecycle = new RtLifecycleCoordinator(
            new RtLifecycleCoordinator.Listener() {
                @Override
                public void processStopping() {
                    RtProgramManager.INSTANCE.resetActive();
                }

                @Override
                public void resourcePackReloadStarting(RtLifecycleCoordinator.ResourcePackEpoch pending) {
                    if (session != null) {
                        RtComposite.INSTANCE.onResourceReloadStart();
                    }
                }

                @Override
                public void resourcePackApplied(RtLifecycleCoordinator.ResourcePackEpoch epoch) {
                    if (session != null) {
                        RtComposite.INSTANCE.onResourcePackApplied();
                    }
                }

                @Override
                public void resourcePackReloadFailed(RtLifecycleCoordinator.ResourcePackEpoch pending,
                                                     Throwable failure) {
                    if (session != null) {
                        RtComposite.INSTANCE.onResourceReloadFailed();
                        if (lifecycle.resourcePackEpoch() != null) {
                            RtComposite.INSTANCE.onResourcePackApplied();
                        }
                    }
                }

                @Override
                public void worldEntered(RtLifecycleCoordinator.WorldEpoch epoch) {
                    if (session != null) {
                        session.onWorldChanged();
                    }
                }

                @Override
                public void worldLeaving(RtLifecycleCoordinator.WorldEpoch epoch) {
                    if (session != null) {
                        session.onWorldChanged();
                    }
                }
            });

    private RtRuntime() {
    }

    public void installHost(RtRuntimeHost installedHost) {
        host = installedHost;
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
    public RtLifecycleCoordinator.ResourcePackEpoch beginResourcePackReload() {
        return lifecycle.beginResourcePackReload();
    }

    /** Attach a manual-reload future; its result is applied on the next client tick. */
    public void trackResourcePackReload(RtLifecycleCoordinator.ResourcePackEpoch pending,
                                        java.util.concurrent.CompletableFuture<?> future) {
        lifecycle.trackResourcePackReload(pending, future);
    }

    /** Reconcile the current level identity at the client-tick boundary. */
    public void observeWorld(Object levelIdentity, long sceneId) {
        lifecycle.observeWorld(levelIdentity, sceneId);
    }

    /** Invalidate render-session world state without changing the host world epoch identity. */
    public void invalidateWorld() {
        if (session != null) {
            session.onWorldChanged();
        }
    }

    public RtLifecycleCoordinator lifecycle() {
        return lifecycle;
    }

    /** Whether a completed resource pack can be replayed into newly created session instances. */
    public boolean hasAppliedResourcePack() {
        return lifecycle.resourcePackEpoch() != null && lifecycle.pendingResourcePackEpoch() == null;
    }

    /** The live contribution instances for the current runtime activation. */
    public CausticaRegistry.RuntimeContributions runtimeContributions() {
        if (session == null) {
            throw new IllegalStateException("No RT render session is active");
        }
        return session.contributions;
    }

    /** Route host-side capture to the current scoped render pass when it exists. */
    public <T extends CausticaRenderPass> T renderPass(ResourceId id, Class<T> type) {
        if (session == null) {
            return null;
        }
        CausticaRenderPass pass = session.contributions.renderPasses().get(id);
        return type.isInstance(pass) ? type.cast(pass) : null;
    }

    /** Whether a program's selected-slot closure matches the live runtime activation. */
    public boolean matchesRuntimeActivation(RtProgramManager.Program program) {
        return session != null && session.matches(program.key().selection());
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
        lifecycle.drainResourcePackCompletions();
        CausticaRegistry.Selection selection = CausticaApi.registry().selection();
        RtProgramManager.INSTANCE.request(selection, RtDeviceBringup.serExtEnabled());
        RtProgramManager.Program candidate = RtProgramManager.INSTANCE.candidate();
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

    public State state() {
        return state;
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
        if (GpuContext.backendOrNull() == null || !GpuContext.backendOrNull().rayTracingProvisioned()) {
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
        boolean providersInstalled = false;
        try {
            SlangRuntime.INSTANCE.resume();
            contributions = CausticaApi.registry().createRuntimeContributions();
            ProviderManager.INSTANCE.bindSceneGeometry(RtComposite.INSTANCE.sceneGeometry());
            ProviderManager.INSTANCE.beginSession(contributions);
            providersInstalled = true;
            session = new Session(renderSessionEpoch, activationEpoch, contributions);
            if (hasAppliedResourcePack()) {
                RtComposite.INSTANCE.onResourcePackApplied();
            }
            if (lifecycle.worldEpoch() != null) {
                session.onWorldChanged();
            }
            state = State.STARTING;
            CausticaMod.LOGGER.info("RT runtime starting; source presentation remains active");
        } catch (Throwable failure) {
            try {
                if (session != null) {
                    session.closeActivation();
                    session = null;
                } else if (providersInstalled || contributions != null) {
                    disposeUnstartedContributions(contributions);
                }
            } finally {
                lifecycle.closeRuntimeActivation(activationEpoch);
                lifecycle.closeRenderSession(renderSessionEpoch);
                state = State.FAILED;
                CausticaMod.LOGGER.error("RT runtime could not create its render session", failure);
            }
        }
    }

    private static void disposeUnstartedContributions(CausticaRegistry.RuntimeContributions contributions) {
        ProviderManager.INSTANCE.beginSession(contributions);
        ProviderManager.INSTANCE.stopProviders();
        ProviderManager.INSTANCE.shutdownResources();
        ProviderManager.INSTANCE.endSession();
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
        private GpuContext context;

        private Session(RtLifecycleCoordinator.RenderSessionEpoch renderSessionEpoch,
                        RtLifecycleCoordinator.RuntimeActivationEpoch activationEpoch,
                        CausticaRegistry.RuntimeContributions contributions) {
            this.renderSessionEpoch = renderSessionEpoch;
            this.activationEpoch = activationEpoch;
            this.contributions = contributions;
        }

        private void onWorldChanged() {
            ProviderManager.INSTANCE.onWorldChanged();
            RtComposite.INSTANCE.onWorldChanged();
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
            if (context == null) {
                context = GpuContext.get();
                if (context == null) {
                    return false;
                }
                INSTANCE.lifecycle.observeDevice(context);
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

        void closeActivation() {
            host().resetFrameBridge();
            ProviderManager.INSTANCE.stopProviders();
            if (context == null) {
                ProviderManager.INSTANCE.shutdownResources();
                ProviderManager.INSTANCE.endSession();
                host().destroyUiPresentation();
                return;
            }

            // Scene producers and CPU workers are stopped. Drain the shared per-device submitter and wait
            // every queue before the remaining providers or runtime owners free session GPU resources.
            context.gpuExecutor().drainAndWaitIdle();
            ProviderManager.INSTANCE.shutdownResources();
            ProviderManager.INSTANCE.endSession();
            host().destroyUiPresentation();
            RtComposite.INSTANCE.destroy();
            host().resetSceneTextures();
            RtDlssFg.INSTANCE.destroy();
            RtFramePresenter.INSTANCE.destroy(context.vk());
            RtReflex.INSTANCE.destroy(context.vk());
            // GpuContext owns per-device infrastructure and survives RT sessions. Client shutdown destroys it.
            context = null;
        }
    }
}
