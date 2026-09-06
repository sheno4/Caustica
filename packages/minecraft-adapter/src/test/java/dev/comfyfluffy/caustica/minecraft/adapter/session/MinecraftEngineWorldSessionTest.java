package dev.comfyfluffy.caustica.minecraft.adapter.session;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.engine.pass.PassKey;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftEngineWorldSessionTest {
    private static final dev.comfyfluffy.caustica.settings.SettingsAccess OPTIONS = new dev.comfyfluffy.caustica.settings.testing.InMemorySettings();
    @Test
    void composesBothProcessHostsOverSharedServicesAndDropsRootSceneLast() {
        List<String> events = new ArrayList<>();
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(OPTIONS);
        List<Object> programs = new ArrayList<>();
        renderHost.api().sessions().add(context -> {
            programs.add(context.program());
            return contribution("core", events);
        });
        minecraftHost.api().sessions().add(context -> {
            programs.add(context.renderSession().program());
            assertEquals(MinecraftDimensionKey.of("minecraft", "overworld"), context.dimension());
            return minecraftContribution("minecraft", events);
        });

        RetainedSceneBackend sceneBackend = new RetainedSceneBackend() {
            @Override public void bind(Supplier<SharedResource<RetainedSceneSnapshot>> capture) {
            }
        };
        MinecraftEngineWorldSession session = new MinecraftEngineWorldSession(renderHost, minecraftHost, GPU, COMPUTE,
                PROGRAMS, sceneBackend, (mesh, source) -> java.util.concurrent.CompletableFuture.completedFuture(dev.comfyfluffy.caustica.api.resource.ResourceOwner.none().retain()), PASSES,
                MinecraftDimensionKey.of("minecraft", "overworld"), new ResourcePackEpoch(2),
                failure -> { throw new AssertionError(failure); });

        assertNotSame(programs.get(0), programs.get(1));
        assertEquals(1, session.services().scenes().snapshot().scenes().size());
        session.resourcePackChanged(new ResourcePackEpoch(3));
        assertEquals(new ResourcePackEpoch(3), session.resourcePackEpoch());
        session.close();

        assertEquals(List.of("minecraft:pack:3", "minecraft:stop", "core:stop",
                "minecraft:close", "core:close"), events);
        assertEquals(0, session.services().scenes().snapshot().scenes().size());
        assertThrows(IllegalStateException.class, session::progress);
    }

    @Test
    void creationFailureInvalidatesMinecraftBeforeSceneSettlementAndAggregatesCleanup() {
        List<String> events = new ArrayList<>();
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(OPTIONS);
        renderHost.api().sessions().add(context -> contribution("core", events));
        minecraftHost.api().sessions().add(context -> minecraftContribution("minecraft", events));
        minecraftHost.api().sessions().add(context -> { throw new IllegalStateException("minecraft-open"); });
        RetainedSceneBackend scenes = new RetainedSceneBackend() {
            @Override public void bind(Supplier<SharedResource<RetainedSceneSnapshot>> capture) {
            }
            @Override public void prepareForSessionClose() {
                events.add("scene:settle");
                throw new IllegalArgumentException("settle");
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new MinecraftEngineWorldSession(renderHost, minecraftHost, GPU, COMPUTE, PROGRAMS, scenes, (mesh, source) -> java.util.concurrent.CompletableFuture.completedFuture(dev.comfyfluffy.caustica.api.resource.ResourceOwner.none().retain()), PASSES,
                        MinecraftDimensionKey.of("minecraft", "overworld"), new ResourcePackEpoch(0),
                        reported -> { throw (RuntimeException) reported; }));

        assertEquals("minecraft-open", failure.getMessage());
        assertEquals(List.of("minecraft:stop", "core:stop", "scene:settle",
                "minecraft:close", "core:close"), events);
        assertEquals("settle", failure.getSuppressed()[0].getMessage());
    }

    private static RenderSessionContribution contribution(String name, List<String> events) {
        return new RenderSessionContribution() {
            @Override public void stop() { events.add(name + ":stop"); }
            @Override public void close() { events.add(name + ":close"); }
        };
    }

    private static MinecraftWorldSessionContribution minecraftContribution(String name, List<String> events) {
        return new MinecraftWorldSessionContribution() {
            @Override public void resourcePackChanged(ResourcePackEpoch epoch) {
                events.add(name + ":pack:" + epoch.generation());
            }
            @Override public void stop() { events.add(name + ":stop"); }
            @Override public void close() { events.add(name + ":close"); }
        };
    }

    private static final GpuDevice GPU = new GpuDevice() {
        @Override public VkDevice vk() { return null; }
        @Override public long vmaAllocator() { return 0; }
        @Override public int[] asyncBufferSharingQueueFamilies() { return new int[] { 0 }; }
        @Override public GpuDescriptorHeap descriptorHeap() { return null; }
    };

    private static final GpuComputeQueue COMPUTE = new GpuComputeQueue() {
        @Override public GpuComputeJob submit(
                java.util.function.Consumer<? super org.lwjgl.vulkan.VkCommandBuffer> recorder,
                java.util.function.Consumer<? super dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion> completion) {
            throw new AssertionError("no compute jobs expected");
        }
        @Override public int[] sharedQueueFamilyIndices() { return new int[] { 0 }; }
    };

    private static final ProgramBackend PROGRAMS = new ProgramBackend() {
        @Override public void compile(ProgramComposition composition,
                                      java.util.function.Consumer<? super Compilation> completion) {
            completion.accept(new Compilation.Succeeded(program()));
        }
        @Override public void publish(CompiledProgram program, Runnable retired) { retired.run(); }
        @Override public void drainPublishedUses() { }

        private CompiledProgram program() {
            return new CompiledProgram() {
                @Override public void close() { }
            };
        }
    };

    private static final PassSchedulerBackend PASSES = new PassSchedulerBackend() {
        @Override public WorldResourceSetup worldResourceSetup() { return new WorldResourceSetup(GPU); }
        @Override public PostEffectSetup postEffectSetup() { return new PostEffectSetup(GPU, 1, 2); }
        @Override public UiSetup uiSetup() { return new UiSetup(GPU, 3); }
        @Override public Invocation<PassFrame> beginWorldResource(PassKey pass) { throw new AssertionError(); }
        @Override public PostInvocation beginPostEffect(PassKey pass) { throw new AssertionError(); }
        @Override public Invocation<UiFrame> beginUi(PassKey pass) { throw new AssertionError(); }
    };
}
