package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import net.minecraft.client.Minecraft;

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

    private RtRuntime() {
    }

    /** Reconcile the requested mode and advance session startup at the client-tick boundary. */
    public void tick(Minecraft client) {
        boolean requested = CausticaConfig.Rt.ENABLED.value();
        if (!requested) {
            if (state == State.STARTING || state == State.ACTIVE) {
                stop(client);
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
        boolean sessionReady = session.tick(client, starting);
        if (RtComposite.INSTANCE.hasFailed()) {
            fail(client);
            return;
        }
        if (!sessionReady) {
            return;
        }
        if (!starting) {
            return;
        }
        if (client.level == null || client.player == null
                || !RtTerrain.isSectionReady(client.player.blockPosition())
                || !RtComposite.INSTANCE.completeStartupBoundary()) {
            return;
        }

        state = State.ACTIVE;
        RtComposite.INSTANCE.resetExposureHistory();
        RtComposite.INSTANCE.resetFailureLatch();
        VanillaRenderController.INSTANCE.resetFailureLatch();
        if (CausticaConfig.Rt.Hdr.ENABLED.value()) {
            client.invalidateSurfaceConfiguration();
        }
        CausticaMod.LOGGER.info("RT runtime active");
    }

    /** Latch the session state consumed by every hook in this render frame. */
    public void beginRenderFrame() {
        frameActive = state == State.ACTIVE;
    }

    public void shutdown() {
        frameActive = false;
        if (session != null) {
            state = State.STOPPING;
            session.close();
            session = null;
        }
        NgxRuntime.INSTANCE.shutdown();
        SlangRuntime.INSTANCE.shutdown();
        state = State.OFF;
    }

    public State state() {
        return state;
    }

    /** True after startup has completed, including setup work immediately before the next render frame. */
    public static boolean active() {
        return INSTANCE.state == State.ACTIVE;
    }

    /** True only for hooks participating in the current, already-latched render frame. */
    public static boolean frameActive() {
        return INSTANCE.frameActive;
    }

    /** True while a session exists, including vanilla-rendered startup. */
    public static boolean hasSession() {
        return INSTANCE.session != null;
    }

    /** PQ belongs to an active RT session; Off and Starting use Minecraft's native SDR swapchain. */
    public static boolean wantsPqSwapchain() {
        return CausticaConfig.Rt.ENABLED.value()
                && active()
                && CausticaConfig.Rt.Hdr.ENABLED.value();
    }

    private void start() {
        if (!RtDeviceBringup.rtRequested()) {
            state = State.FAILED;
            CausticaMod.LOGGER.warn("RT runtime unavailable: the Vulkan device was not provisioned for ray tracing");
            return;
        }
        SlangRuntime.INSTANCE.resume();
        ProviderManager.INSTANCE.startSession();
        session = new Session();
        state = State.STARTING;
        CausticaMod.LOGGER.info("RT runtime starting; vanilla presentation remains active");
    }

    private void stop(Minecraft client) {
        state = State.STOPPING;
        frameActive = false;
        client.invalidateSurfaceConfiguration();
        session.close();
        session = null;
        state = State.OFF;
        if (client.level != null) {
            client.levelExtractor.allChanged();
        }
        CausticaMod.LOGGER.info("RT runtime off; vanilla presentation restored");
    }

    private void fail(Minecraft client) {
        state = State.STOPPING;
        frameActive = false;
        client.invalidateSurfaceConfiguration();
        session.close();
        session = null;
        state = State.FAILED;
        if (client.level != null) {
            client.levelExtractor.allChanged();
        }
        CausticaMod.LOGGER.warn("RT runtime startup failed; vanilla presentation remains active");
    }

    private static final class Session {
        private GpuContext context;

        boolean tick(Minecraft client, boolean starting) {
            if (context == null) {
                context = GpuContext.get();
                if (context == null) {
                    return false;
                }
            }

            boolean resourcesReady = RtComposite.INSTANCE.ensureResourcesReady(context);
            if (resourcesReady) {
                RtFrameStats.FRAME.beginIfInactive();
                ProviderManager.INSTANCE.updateScenes();
            }
            if (client.level == null) {
                return false;
            }
            if (starting && resourcesReady) {
                RtTerrain.frame(context);
            }
            var mainTarget = client.gameRenderer.mainRenderTarget();
            if (starting && (mainTarget == null || !RtComposite.INSTANCE.ensurePresentationResourcesReady(
                    context, mainTarget.width, mainTarget.height))) {
                return false;
            }
            if (CausticaConfig.Rt.Fg.ENABLED.value()) {
                RtDlssFg.INSTANCE.probeAvailabilityOnce();
            }
            return resourcesReady;
        }

        void close() {
            WorldRenderScaler.INSTANCE.destroy();
            RtUiOverlay.destroy();
            if (context == null) {
                RtWorkerPool.INSTANCE.shutdown();
                return;
            }

            ProviderManager.INSTANCE.shutdown();
            RtWorkerPool.INSTANCE.shutdown();
            RtComposite.INSTANCE.destroy();
            RtEntityTextures.INSTANCE.reset();
            RtDlssFg.INSTANCE.destroy();
            if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
                RtFramePresenter.INSTANCE.destroy(device);
                RtReflex.INSTANCE.destroy(device.vkDevice());
            }
            SlangRuntime.INSTANCE.requestShutdownWhenIdle();
            context.destroy();
            context = null;
        }
    }
}
