package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
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
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EngineWorldSessionTest {
    private static final dev.comfyfluffy.caustica.settings.OptionLookup OPTIONS = id -> { throw new AssertionError(id); };
    @Test
    void ownsGenericRenderContributionsAndDropsRootSceneLast() {
        List<String> events = new ArrayList<>();
        List<RetainedSceneSnapshot> snapshots = new ArrayList<>();
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        renderHost.api().sessions().add(context -> contribution("core", events));

        EngineWorldSession session = new EngineWorldSession(renderHost, GPU, PROGRAMS,
                (snapshot, retired) -> { snapshots.add(snapshot); retired.run(); }, PASSES,
                failure -> { throw new AssertionError(failure); });

        assertEquals(1, session.services().scenes().snapshot().scenes().size());
        session.progress();
        session.close();

        assertEquals(List.of("core:stop", "core:close"), events);
        assertEquals(0, snapshots.getLast().scenes().size());
        assertThrows(IllegalStateException.class, session::progress);
    }

    private static RenderSessionContribution contribution(String name, List<String> events) {
        return new RenderSessionContribution() {
            @Override public void stop() { events.add(name + ":stop"); }
            @Override public void close() { events.add(name + ":close"); }
        };
    }

    private static final GpuDevice GPU = new GpuDevice() {
        @Override public VkDevice vk() { return null; }
        @Override public long vmaAllocator() { return 0; }
        @Override public GpuDescriptorHeap descriptorHeap() { return null; }
        @Override public void retireAfterUse(Runnable cleanup) { cleanup.run(); }
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
                @Override public int implementationIndex(ProgramKey key) { return (int) key.sequence(); }
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
