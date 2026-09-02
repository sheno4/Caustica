package dev.comfyfluffy.caustica.engine.session;

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
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneContentSnapshot;
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

        RetainedSceneBackend scenes = new RetainedSceneBackend() {
            @Override public void publish(RetainedSceneSnapshot snapshot, Runnable published) {
                snapshots.add(snapshot);
                published.run();
            }
            @Override public void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published) {
                published.run();
            }
        };
        EngineWorldSession session = new EngineWorldSession(renderHost, GPU, COMPUTE, PROGRAMS, scenes, PASSES,
                failure -> { throw new AssertionError(failure); });

        assertEquals(1, session.services().scenes().snapshot().scenes().size());
        session.progress();
        session.close();

        assertEquals(List.of("core:stop", "core:close"), events);
        assertEquals(0, snapshots.getLast().scenes().size());
        assertThrows(IllegalStateException.class, session::progress);
    }

    @Test
    void closeSettlesAcceptedSceneWorkWithoutAnotherFrame() {
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        AsyncSceneBackend scenes = new AsyncSceneBackend();
        EngineWorldSession session = new EngineWorldSession(renderHost, GPU, COMPUTE, PROGRAMS, scenes, PASSES,
                failure -> { throw new AssertionError(failure); });

        session.close();

        assertEquals(true, scenes.prepared);
    }

    @Test
    void closeContinuesAfterSceneSettlementFailureAndSuppressesLaterFailures() {
        List<String> events = new ArrayList<>();
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        renderHost.api().sessions().add(context -> contribution("core", events));
        RetainedSceneBackend scenes = new RetainedSceneBackend() {
            @Override public void publish(RetainedSceneSnapshot snapshot, Runnable published) {
                published.run();
            }
            @Override public void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published) {
                published.run();
            }
            @Override public void prepareForSessionClose() { throw new IllegalStateException("settle"); }
        };
        EngineWorldSession session = new EngineWorldSession(renderHost, GPU, COMPUTE, PROGRAMS, scenes, PASSES,
                failure -> { throw new AssertionError(failure); });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> session.close(new EngineWorldSession.CloseParticipant() {
                    @Override public void invalidate() { }
                    @Override public void drain() { throw new IllegalArgumentException("participant"); }
                }));

        assertEquals("settle", failure.getMessage());
        assertEquals("participant", failure.getSuppressed()[0].getMessage());
        assertEquals(List.of("core:stop", "core:close"), events);
    }

    @Test
    void creationFailureStillCrossesSceneSettlementBoundary() {
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        renderHost.api().sessions().add(context -> { throw new IllegalStateException("open"); });
        AsyncSceneBackend scenes = new AsyncSceneBackend();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new EngineWorldSession(renderHost, GPU, COMPUTE, PROGRAMS, scenes, PASSES,
                        reported -> { throw (RuntimeException) reported; }));

        assertEquals("open", failure.getMessage());
        assertEquals(true, scenes.prepared);
    }

    @Test
    void creationFailureRemainsPrimaryWhenSettlementAlsoFails() {
        RenderSessionHost renderHost = new RenderSessionHost(OPTIONS);
        renderHost.api().sessions().add(context -> { throw new IllegalStateException("open"); });
        RetainedSceneBackend scenes = new RetainedSceneBackend() {
            @Override public void publish(RetainedSceneSnapshot snapshot, Runnable published) {
                published.run();
            }
            @Override public void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published) {
                published.run();
            }
            @Override public void prepareForSessionClose() { throw new IllegalArgumentException("settle"); }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new EngineWorldSession(renderHost, GPU, COMPUTE, PROGRAMS, scenes, PASSES,
                        reported -> { throw (RuntimeException) reported; }));

        assertEquals("open", failure.getMessage());
        assertEquals("settle", failure.getSuppressed()[0].getMessage());
    }

    private static final class AsyncSceneBackend implements RetainedSceneBackend {
        private final List<Runnable> pending = new ArrayList<>();
        private final List<Runnable> completed = new ArrayList<>();
        private boolean prepared;

        @Override public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable published) {
            pending.add(published);
        }
        @Override public synchronized void publishContent(RetainedSceneContentSnapshot snapshot,
                                                          Runnable published) {
            pending.add(published);
        }
        @Override public synchronized void prepareForSessionClose() {
            prepared = true;
            completed.addAll(pending);
            pending.clear();
        }
        @Override public void progress() {
            List<Runnable> ready;
            synchronized (this) {
                ready = List.copyOf(completed);
                completed.clear();
            }
            ready.forEach(Runnable::run);
        }
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
        @Override public int[] asyncBufferSharingQueueFamilies() { return new int[] { 0 }; }
        @Override public GpuDescriptorHeap descriptorHeap() { return null; }
        @Override public void retireAfterUse(Runnable cleanup) { cleanup.run(); }
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
