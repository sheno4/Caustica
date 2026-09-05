package dev.comfyfluffy.caustica.minecraft.adapter.session;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.MeshPreparationBackend;
import dev.comfyfluffy.caustica.engine.session.EngineWorldSession;
import dev.comfyfluffy.caustica.engine.session.EnvironmentSelectionScope;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.Objects;
import java.util.function.Consumer;

/** Composes one generic render world with its Minecraft contribution epoch. */
public final class MinecraftEngineWorldSession implements AutoCloseable {
    private final EngineWorldSession engine;
    private final MinecraftWorldSession minecraft;
    private boolean closed;

    public MinecraftEngineWorldSession(
            RenderSessionHost renderHost,
            MinecraftWorldSessionHost minecraftHost,
            GpuDevice gpu,
            GpuComputeQueue compute,
            ProgramBackend programs,
            RetainedSceneBackend scenes,
            MeshPreparationBackend meshes,
            PassSchedulerBackend passes,
            MinecraftDimensionKey dimension,
            ResourcePackEpoch resourcePackEpoch,
            Consumer<? super Throwable> failures) {
        Objects.requireNonNull(minecraftHost, "minecraftHost");
        Objects.requireNonNull(failures, "failures");
        EngineWorldSession openedEngine = new EngineWorldSession(
                renderHost, gpu, compute, programs, scenes, meshes, passes, failures);
        MinecraftWorldSession openedMinecraft = null;
        try {
            openedMinecraft = minecraftHost.openSession(openedEngine.services(),
                    (owner, scene) -> adapt(openedEngine.openEnvironment(owner)),
                    openedEngine.rootScene(), dimension, resourcePackEpoch,
                    failure -> failures.accept(failure.cause()));
            openedMinecraft.processPendingChanges();
        } catch (Throwable failure) {
            try {
                if (openedMinecraft == null) {
                    openedEngine.close();
                } else {
                    MinecraftWorldSession closingMinecraft = openedMinecraft;
                    openedEngine.close(new EngineWorldSession.CloseParticipant() {
                        @Override public void invalidate() { closingMinecraft.beginClose(); }
                        @Override public void drain() { closingMinecraft.finishClose(); }
                    });
                }
            } catch (Throwable cleanupFailure) {
                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Minecraft engine world session creation failed", failure);
        }
        engine = openedEngine;
        minecraft = openedMinecraft;
    }

    public dev.comfyfluffy.caustica.engine.session.EngineSessionServices services() {
        return engine.services();
    }

    public SceneId rootScene() { return engine.rootScene(); }
    public ResourcePackEpoch resourcePackEpoch() { return minecraft.resourcePackEpoch(); }

    public void resourcePackChanged(ResourcePackEpoch epoch) {
        requireOpen();
        minecraft.resourcePackChanged(epoch);
    }

    public void progress() {
        requireOpen();
        engine.progressWorld(minecraft::processPendingChanges);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        engine.close(new EngineWorldSession.CloseParticipant() {
            @Override public void invalidate() { minecraft.beginClose(); }
            @Override public void drain() { minecraft.finishClose(); }
        });
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Minecraft engine world session is closed");
    }

    private static MinecraftEnvironmentScope adapt(EnvironmentSelectionScope scope) {
        return new MinecraftEnvironmentScope() {
            @Override public void select(EnvironmentBinding<?> binding) {
                scope.select(binding);
            }
            @Override public void invalidate() { scope.invalidate(); }
            @Override public void drain() { scope.drain(); }
        };
    }
}
