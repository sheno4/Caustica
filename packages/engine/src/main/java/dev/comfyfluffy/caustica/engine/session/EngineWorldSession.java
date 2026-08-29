package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.Objects;
import java.util.function.Consumer;

/** Concrete shared engine and Minecraft world epoch, closed before its injected device is destroyed. */
public final class EngineWorldSession implements AutoCloseable {
    private final EngineSessionServices services;
    private final SceneId rootScene;
    private final EngineRenderSession renderSession;
    private final EngineMinecraftWorldSession minecraftSession;
    private boolean closed;

    public EngineWorldSession(
            RenderSessionHost renderHost,
            MinecraftWorldSessionHost minecraftHost,
            GpuDevice gpu,
            ProgramBackend programs,
            RetainedSceneBackend scenes,
            PassSchedulerBackend passes,
            MinecraftDimensionKey dimension,
            ResourcePackEpoch resourcePackEpoch,
            Consumer<? super Throwable> failures) {
        Objects.requireNonNull(renderHost, "renderHost");
        Objects.requireNonNull(minecraftHost, "minecraftHost");
        Objects.requireNonNull(failures, "failures");
        services = new EngineSessionServices(gpu, programs, scenes, passes,
                failures::accept, failures::accept, (pass, failure) -> failures.accept(failure));
        rootScene = services.scenes().createScene();
        EngineRenderSession openedRender = null;
        EngineMinecraftWorldSession openedMinecraft = null;
        try {
            openedRender = renderHost.openSession(services, failure -> failures.accept(failure.cause()));
            openedRender.processPendingChanges();
            openedMinecraft = minecraftHost.openSession(services, services.scenes()::openEnvironment,
                    rootScene, dimension, resourcePackEpoch,
                    failure -> failures.accept(failure.cause()));
            openedMinecraft.processPendingChanges();
        } catch (Throwable failure) {
            if (openedMinecraft != null) openedMinecraft.close();
            if (openedRender != null) openedRender.close();
            services.scenes().dropScene(rootScene);
            services.progress();
            services.close();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("engine world session creation failed", failure);
        }
        renderSession = openedRender;
        minecraftSession = openedMinecraft;
    }

    public EngineSessionServices services() { return services; }
    public SceneId rootScene() { return rootScene; }
    public ResourcePackEpoch resourcePackEpoch() { return minecraftSession.resourcePackEpoch(); }

    /** Applies process registration changes and renderer backend completions. */
    public void progress() {
        requireOpen();
        renderSession.processPendingChanges();
        minecraftSession.processPendingChanges();
        services.progress();
    }

    public void resourcePackChanged(ResourcePackEpoch epoch) {
        requireOpen();
        minecraftSession.resourcePackChanged(epoch);
    }

    /**
     * Stops Minecraft producers, tears down all owner scopes, drops the root scene, and releases service
     * bookkeeping. The injected backend/device owner may begin its own destruction only after this returns.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        minecraftSession.close();
        renderSession.close();
        services.scenes().dropScene(rootScene);
        services.progress();
        services.close();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("engine world session is closed");
    }
}
