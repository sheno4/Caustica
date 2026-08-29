package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.engine.pass.PassKey;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EngineSessionServicesTest {
    @Test
    void twoContributionsShareTheRendererServicesButRetireTheirPassesIndependently() {
        ImmediatePassBackend passBackend = new ImmediatePassBackend();
        EngineSessionServices services = services(passBackend);
        RenderSessionHost host = new RenderSessionHost();
        List<String> events = new ArrayList<>();
        List<Object> programChannels = new ArrayList<>();

        var firstRegistration = host.api().sessions().add(context -> {
            assertSame(GPU, context.gpu());
            programChannels.add(context.program());
            context.passes().addWorldResourcePass(setup -> pass(
                    () -> events.add("first:record"), () -> events.add("first:pass-close")));
            var mesh = context.geometry().newMesh(ShaderDataType.create("stop-time mesh"));
            return new RenderSessionContribution() {
                @Override public void stop() {
                    events.add("first:stop");
                    context.geometry().submit(new RetainedBatch<>(
                            List.of(new GeometryChannel.DropMesh<>(mesh)),
                            () -> events.add("first:retired")));
                }
                @Override public void close() { events.add("first:close"); }
            };
        });
        host.api().sessions().add(context -> {
            assertSame(GPU, context.gpu());
            programChannels.add(context.program());
            context.passes().addWorldResourcePass(setup -> pass(
                    () -> events.add("second:record"), () -> events.add("second:pass-close")));
            return contribution("second", events);
        });

        EngineRenderSession session = host.openSession(services, failure -> {
            throw new AssertionError(failure);
        });
        session.processPendingChanges();
        assertNotSame(programChannels.get(0), programChannels.get(1));

        services.passes().recordWorldResources();
        firstRegistration.close();
        session.processPendingChanges();
        services.passes().recordWorldResources();
        session.close();
        services.close();

        assertEquals(List.of(
                "first:record", "second:record",
                "first:stop", "first:pass-close", "first:retired", "first:close",
                "second:record",
                "second:stop", "second:pass-close", "second:close"), events);
    }

    @Test
    void servicesExposeOneHostStoreAndRequireScopeLifecycleBeforeDeviceBoundary() {
        EngineSessionServices services = services(new ImmediatePassBackend());
        ContributionScope first = services.create(new ContributionOwner(1));
        ContributionScope second = services.create(new ContributionOwner(2));

        assertSame(GPU, first.gpu());
        assertSame(GPU, second.gpu());
        assertNotSame(first.geometry(), second.geometry());
        assertNotSame(first.lights(), second.lights());
        assertNotSame(first.passes(), second.passes());
        assertThrows(IllegalStateException.class, services::close);

        teardown(first);
        teardown(second);
        services.progress();
        services.close();
        assertThrows(IllegalStateException.class, () -> services.create(new ContributionOwner(3)));
    }

    private static void teardown(ContributionScope scope) {
        scope.quiesce();
        scope.invalidate();
        scope.drain();
        scope.close();
    }

    private static EngineSessionServices services(PassSchedulerBackend passes) {
        ProgramBackend programs = new ProgramBackend() {
            @Override
            public void compile(ProgramComposition composition,
                                java.util.function.Consumer<? super Compilation> completion) {
                throw new AssertionError("no program registrations expected");
            }

            @Override public void publish(CompiledProgram program, Runnable previousRetired) {
                previousRetired.run();
            }
            @Override public void drainPublishedUses() { }
        };
        return new EngineSessionServices(GPU, programs,
                (snapshot, previousRetired) -> previousRetired.run(), passes,
                failure -> { throw new AssertionError(failure); },
                failure -> { throw new AssertionError(failure); },
                (pass, failure) -> { throw new AssertionError(failure); });
    }

    private static Pass<PassFrame> pass(Runnable record, Runnable close) {
        return new Pass<>() {
            @Override public void record(PassFrame frame) { record.run(); }
            @Override public void close() { close.run(); }
        };
    }

    private static RenderSessionContribution contribution(String name, List<String> events) {
        return new RenderSessionContribution() {
            @Override public void stop() { events.add(name + ":stop"); }
            @Override public void close() { events.add(name + ":close"); }
        };
    }

    private static final class ImmediatePassBackend implements PassSchedulerBackend {
        @Override public WorldResourceSetup worldResourceSetup() { return new WorldResourceSetup(GPU); }
        @Override public PostEffectSetup postEffectSetup() { return new PostEffectSetup(GPU, 1, 2); }
        @Override public UiSetup uiSetup() { return new UiSetup(GPU, 3); }
        @Override public Invocation<PassFrame> beginWorldResource(PassKey pass) { return invocation(FRAME); }
        @Override public PostInvocation beginPostEffect(PassKey pass) { throw new AssertionError(); }
        @Override public Invocation<UiFrame> beginUi(PassKey pass) { throw new AssertionError(); }

        private static <F extends PassFrame> Invocation<F> invocation(F frame) {
            return new Invocation<>() {
                @Override public F frame() { return frame; }
                @Override public void submit(Runnable drained) { drained.run(); }
                @Override public void abandon(Throwable failure, Runnable drained) { drained.run(); }
            };
        }
    }

    private static final GpuDevice GPU = new GpuDevice() {
        @Override public VkDevice vk() { return null; }
        @Override public long vmaAllocator() { return 0; }
        @Override public GpuDescriptorHeap descriptorHeap() { return null; }
        @Override public void retireAfterUse(Runnable cleanup) { cleanup.run(); }
    };

    private static final PassFrame FRAME = new PassFrame() {
        @Override public VkCommandBuffer commandBuffer() { return null; }
        @Override public GpuFrameUse gpuUse() { return null; }
        @Override public long frameIndex() { return 1; }
        @Override public dev.comfyfluffy.caustica.api.view.SceneView view() { return null; }
        @Override public double timeSeconds() { return 2.0; }
        @Override public double metersPerSceneUnit() { return 1.0; }
        @Override public int renderWidth() { return 1280; }
        @Override public int renderHeight() { return 720; }
    };
}
