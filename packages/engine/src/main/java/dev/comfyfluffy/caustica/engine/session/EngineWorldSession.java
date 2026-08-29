package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;

import java.util.Objects;
import java.util.function.Consumer;

/** Generic render-session and root-scene epoch, closed before its injected device is destroyed. */
public final class EngineWorldSession implements AutoCloseable {
    private final EngineSessionServices services;
    private final SceneId rootScene;
    private final EngineRenderSession renderSession;
    private boolean closed;

    public EngineWorldSession(
            RenderSessionHost renderHost,
            GpuDevice gpu,
            ProgramBackend programs,
            RetainedSceneBackend scenes,
            PassSchedulerBackend passes,
            Consumer<? super Throwable> failures) {
        Objects.requireNonNull(renderHost, "renderHost");
        Objects.requireNonNull(failures, "failures");
        services = new EngineSessionServices(gpu, programs, scenes, passes,
                failures::accept, failures::accept, (pass, failure) -> failures.accept(failure));
        rootScene = services.scenes().createScene();
        EngineRenderSession openedRender = null;
        try {
            openedRender = renderHost.openSession(services, failure -> failures.accept(failure.cause()));
            openedRender.processPendingChanges();
        } catch (Throwable failure) {
            if (openedRender != null) openedRender.close();
            services.scenes().dropScene(rootScene);
            services.progress();
            services.close();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("engine world session creation failed", failure);
        }
        renderSession = openedRender;
    }

    public EngineSessionServices services() { return services; }
    public SceneId rootScene() { return rootScene; }

    /** Creates one environment-selection scope borrowing this session's root scene. */
    public EnvironmentSelectionScope openEnvironment(ContributionOwner owner) {
        requireOpen();
        return services.scenes().openEnvironment(owner, rootScene);
    }

    /** Applies render registrations, then host-owned world changes, before backend completions. */
    public void progressWorld(Runnable hostWorldProgress) {
        requireOpen();
        renderSession.processPendingChanges();
        Objects.requireNonNull(hostWorldProgress, "hostWorldProgress").run();
        services.progress();
    }

    public void progress() {
        progressWorld(() -> { });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        renderSession.close();
        services.scenes().dropScene(rootScene);
        services.progress();
        services.close();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("engine world session is closed");
    }
}
