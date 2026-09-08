package dev.comfyfluffy.caustica.engine.pass;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PassSessionTest {
    @Test
    void worldResourceUploadRecordsBeforeTheRendererStartsTracing() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        List<String> events = new ArrayList<>();
        session.openChannel().addWorldResourcePass(
                setup -> pass(frame -> events.add("upload"), () -> { }));

        session.recordWorldResources();
        events.add("trace");

        assertEquals(List.of("upload", "trace"), events);
    }

    @Test
    void dispatchesEachStageInGlobalAcceptanceOrder() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel first = session.openChannel();
        PassContributionChannel second = session.openChannel();
        List<String> events = new ArrayList<>();

        first.addWorldResourcePass(setup -> pass(frame -> events.add("world-a"), () -> events.add("close-a")));
        second.addPostEffectPass(id("post-b"),
                setup -> pass(frame -> events.add("post-b"), () -> events.add("close-b")));
        first.addPostEffectPass(id("post-a"),
                setup -> pass(frame -> events.add("post-a"), () -> events.add("close-c")));
        second.addUiPass(id("ui-b"),
                setup -> pass(frame -> events.add("ui-b"), () -> events.add("close-d")));

        session.recordWorldResources();
        session.recordPostEffects();
        session.recordUi();

        assertEquals(List.of("world-a", "post-b", "post-a", "ui-b"), events);
        assertEquals(List.of(0L, 1L, 2L, 3L), backend.begun);
        assertEquals(2, backend.postValidations);
    }

    @Test
    void registrationRemovalWaitsForItsSubmittedFrameUse() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        AtomicInteger records = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var registration = channel.addWorldResourcePass(
                setup -> pass(frame -> records.incrementAndGet(), closes::incrementAndGet));

        session.recordWorldResources();
        registration.close();
        session.recordWorldResources();
        session.progress();

        assertEquals(1, records.get());
        assertEquals(0, closes.get());
        backend.drainAll();
        session.progress();
        assertEquals(1, closes.get());
    }

    @Test
    void recordFailureAbandonsFrameDisablesPassAndIsolatesFollowingPass() {
        ManualBackend backend = new ManualBackend();
        List<Throwable> failures = new ArrayList<>();
        PassSession session = new PassSession(backend, (pass, failure) -> failures.add(failure));
        PassContributionChannel channel = session.openChannel();
        AtomicInteger following = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        channel.addWorldResourcePass(setup -> pass(frame -> {
            throw new IllegalArgumentException("bad commands");
        }, closed::incrementAndGet));
        channel.addWorldResourcePass(setup -> pass(frame -> following.incrementAndGet(), () -> { }));

        session.recordWorldResources();
        session.recordWorldResources();

        assertEquals(1, failures.size());
        assertEquals(2, following.get());
        assertEquals(1, backend.abandoned);
        backend.drainAll();
        session.progress();
        assertEquals(1, closed.get());
    }

    @Test
    void postChainValidationFailureUsesTheAbandonedFramePath() {
        ManualBackend backend = new ManualBackend();
        backend.rejectPost = true;
        List<Throwable> failures = new ArrayList<>();
        PassSession session = new PassSession(backend, (pass, failure) -> failures.add(failure));
        PassContributionChannel channel = session.openChannel();
        AtomicInteger records = new AtomicInteger();
        channel.addPostEffectPass(id("post"),
                setup -> pass(frame -> records.incrementAndGet(), () -> { }));

        session.recordPostEffects();
        session.recordPostEffects();

        assertEquals(1, records.get());
        assertEquals(1, failures.size());
        assertEquals(1, backend.abandoned);
    }

    @Test
    void resolvesPresentAnchorsAndUsesAcceptanceOrderForOtherTies() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel first = session.openChannel();
        PassContributionChannel second = session.openChannel();
        List<String> events = new ArrayList<>();
        PassId bloom = id("bloom");
        PassId grade = id("grade");
        PassId prefilter = id("prefilter");

        first.addPostEffectPass(grade, PassPlacement.after(bloom),
                setup -> pass(frame -> events.add("grade"), () -> { }));
        second.addPostEffectPass(id("unconstrained"),
                setup -> pass(frame -> events.add("unconstrained"), () -> { }));
        second.addPostEffectPass(bloom,
                setup -> pass(frame -> events.add("bloom"), () -> { }));
        first.addPostEffectPass(prefilter, PassPlacement.before(bloom),
                setup -> pass(frame -> events.add("prefilter"), () -> { }));

        session.recordPostEffects();

        assertEquals(List.of("unconstrained", "prefilter", "bloom", "grade"), events);
        assertEquals(List.of(1L, 3L, 2L, 0L), backend.begun);
    }

    @Test
    void ignoresAnAbsentAnchor() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        List<String> events = new ArrayList<>();

        channel.addUiPass(id("marker"), PassPlacement.before(id("absent-hud")),
                setup -> pass(frame -> events.add("marker"), () -> { }));
        channel.addUiPass(id("overlay"),
                setup -> pass(frame -> events.add("overlay"), () -> { }));

        session.recordUi();

        assertEquals(List.of("marker", "overlay"), events);
    }

    @Test
    void rejectsTheRegistrationThatMakesAnExistingMissingAnchorCycle() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        AtomicInteger rejectedClose = new AtomicInteger();
        PassId first = id("first");
        PassId second = id("second");
        channel.addPostEffectPass(first, PassPlacement.after(second),
                setup -> pass(frame -> { }, () -> { }));

        assertThrows(IllegalArgumentException.class,
                () -> channel.addPostEffectPass(second, PassPlacement.after(first),
                        setup -> pass(frame -> { }, rejectedClose::incrementAndGet)));

        assertEquals(1, rejectedClose.get());
        session.recordPostEffects();
        assertEquals(List.of(0L), backend.begun);
    }

    @Test
    void rejectsDuplicateLiveIdsAndReusesAnIdAsSoonAsTheOldRegistrationStops() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        AtomicInteger rejectedClose = new AtomicInteger();
        PassId shared = id("shared");
        PassRegistration first = channel.addUiPass(shared, setup -> pass(frame -> { }, () -> { }));

        assertThrows(IllegalStateException.class,
                () -> channel.addUiPass(shared,
                        setup -> pass(frame -> { }, rejectedClose::incrementAndGet)));
        assertEquals(1, rejectedClose.get());

        first.close();
        channel.addUiPass(shared, setup -> pass(frame -> { }, () -> { }));
        session.recordUi();

        assertEquals(List.of(1L), backend.begun);
    }

    @Test
    void rejectsSelfAnchorsBeforeCreatingThePass() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        AtomicInteger factories = new AtomicInteger();
        PassId self = id("self");

        assertThrows(IllegalArgumentException.class,
                () -> channel.addPostEffectPass(self, PassPlacement.before(self), setup -> {
                    factories.incrementAndGet();
                    return pass(frame -> { }, () -> { });
                }));
        assertEquals(0, factories.get());
    }

    @Test
    void idsAreStageLocalAndBecomeReusableWhenFailureDisablesAPass() {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        PassId shared = id("cross-stage");
        channel.addPostEffectPass(shared, setup -> pass(frame -> {
            throw new IllegalStateException("disable this post pass");
        }, () -> { }));
        channel.addUiPass(shared, setup -> pass(frame -> { }, () -> { }));

        session.recordPostEffects();
        channel.addPostEffectPass(shared, setup -> pass(frame -> { }, () -> { }));
        session.recordPostEffects();
        session.recordUi();

        assertEquals(List.of(0L, 2L, 1L), backend.begun);
    }

    @Test
    void quiesceWaitsForAnAlreadyRunningCallbackAndRejectsNewFactories() throws Exception {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel channel = session.openChannel();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        channel.addWorldResourcePass(setup -> pass(frame -> {
            entered.countDown();
            await(release);
        }, () -> { }));

        Thread recorder = Thread.ofPlatform().start(session::recordWorldResources);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch quiesced = new CountDownLatch(1);
        Thread stopper = Thread.ofPlatform().start(() -> {
            channel.quiesce();
            quiesced.countDown();
        });
        assertFalse(quiesced.await(100, TimeUnit.MILLISECONDS));
        release.countDown();
        assertTrue(quiesced.await(5, TimeUnit.SECONDS));
        recorder.join();
        stopper.join();

        assertThrows(IllegalStateException.class,
                () -> channel.addWorldResourcePass(setup -> pass(frame -> { }, () -> { })));
    }

    @Test
    void ownerDrainClosesOnlyAfterAllUsesAndSessionCloseCoversRemainingOwners() throws Exception {
        ManualBackend backend = new ManualBackend();
        PassSession session = new PassSession(backend, (pass, failure) -> { });
        PassContributionChannel first = session.openChannel();
        PassContributionChannel second = session.openChannel();
        List<String> closes = new ArrayList<>();
        first.addWorldResourcePass(setup -> pass(frame -> { }, () -> closes.add("first")));
        second.addWorldResourcePass(setup -> pass(frame -> { }, () -> closes.add("second")));
        session.recordWorldResources();
        first.quiesce();
        first.invalidate();

        Thread drain = Thread.ofPlatform().start(() -> first.drain());
        backend.drainFirst();
        drain.join(5_000);
        assertFalse(drain.isAlive());
        assertEquals(List.of("first"), closes);

        Thread close = Thread.ofPlatform().start(session::close);
        backend.drainAll();
        close.join(5_000);
        assertFalse(close.isAlive());
        assertEquals(List.of("first", "second"), closes);
    }

    private static <F extends PassFrame> Pass<F> pass(
            java.util.function.Consumer<F> record, Runnable close) {
        return new Pass<>() {
            @Override
            public void record(F frame) {
                record.accept(frame);
            }

            @Override
            public void close() {
                close.run();
            }
        };
    }

    private static PassId id(String path) {
        return new PassId("test", path);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class ManualBackend implements PassSchedulerBackend {
        private final List<Long> begun = new ArrayList<>();
        private final List<Runnable> drains = new ArrayList<>();
        private int postValidations;
        private int abandoned;
        private boolean rejectPost;

        @Override
        public WorldResourceSetup worldResourceSetup() {
            return new WorldResourceSetup(GPU);
        }

        @Override
        public PostEffectSetup postEffectSetup() {
            return new PostEffectSetup(GPU, 1, 2);
        }

        @Override
        public UiSetup uiSetup() {
            return new UiSetup(GPU, 3);
        }

        @Override
        public Invocation<PassFrame> beginWorldResource(PassKey pass) {
            return invocation(pass, FRAME);
        }

        @Override
        public PostInvocation beginPostEffect(PassKey pass) {
            begun.add(pass.sequence());
            return new PostInvocation() {
                @Override
                public PostEffectFrame frame() {
                    return POST_FRAME;
                }

                @Override
                public void validateOutputChain() {
                    postValidations++;
                    if (rejectPost) {
                        throw new IllegalStateException("invalid output chain");
                    }
                }

                @Override
                public void submit(Runnable drained) {
                    drains.add(drained);
                }

                @Override
                public void abandon(Throwable failure, Runnable drained) {
                    abandoned++;
                    drains.add(drained);
                }
            };
        }

        @Override
        public Invocation<UiFrame> beginUi(PassKey pass) {
            return invocation(pass, UI_FRAME);
        }

        private <F extends PassFrame> Invocation<F> invocation(PassKey pass, F frame) {
            begun.add(pass.sequence());
            return new Invocation<>() {
                @Override
                public F frame() {
                    return frame;
                }

                @Override
                public void submit(Runnable drained) {
                    drains.add(drained);
                }

                @Override
                public void abandon(Throwable failure, Runnable drained) {
                    abandoned++;
                    drains.add(drained);
                }
            };
        }

        private void drainFirst() {
            drains.removeFirst().run();
        }

        private void drainAll() {
            while (!drains.isEmpty()) {
                drains.removeFirst().run();
            }
        }
    }

    private static final GpuDevice GPU = new GpuDevice() {
        @Override public VkDevice vk() { return null; }
        @Override public long vmaAllocator() { return 0; }
        @Override public int[] asyncBufferSharingQueueFamilies() { return new int[] { 0 }; }
        @Override public GpuDescriptorHeap descriptorHeap() { return null; }
    };

    private static final PassFrame FRAME = new PassFrame() {
        @Override public VkCommandBuffer commandBuffer() { return null; }
        @Override public void retain(dev.comfyfluffy.caustica.api.resource.ResourceOwner resource) { throw new AssertionError(); }
        @Override public long frameIndex() { return 1; }
        @Override public dev.comfyfluffy.caustica.api.view.SceneView view() { return null; }
        @Override public double timeSeconds() { return 2.0; }
        @Override public double metersPerSceneUnit() { return 1.0; }
        @Override public int renderWidth() { return 1920; }
        @Override public int renderHeight() { return 1080; }
    };

    private static final PostEffectFrame POST_FRAME = new PostEffectFrame() {
        @Override public dev.comfyfluffy.caustica.api.vulkan.GpuImage sceneColor() { return null; }
        @Override public dev.comfyfluffy.caustica.api.vulkan.GpuImage acquireSceneColorOutput() { return null; }
        @Override public dev.comfyfluffy.caustica.api.vulkan.GpuImage exposureImage() { return null; }
        @Override public VkCommandBuffer commandBuffer() { return null; }
        @Override public void retain(dev.comfyfluffy.caustica.api.resource.ResourceOwner resource) { throw new AssertionError(); }
        @Override public long frameIndex() { return 1; }
        @Override public dev.comfyfluffy.caustica.api.view.SceneView view() { return null; }
        @Override public double timeSeconds() { return 2.0; }
        @Override public double metersPerSceneUnit() { return 1.0; }
        @Override public int renderWidth() { return 1920; }
        @Override public int renderHeight() { return 1080; }
    };

    private static final UiFrame UI_FRAME = new UiFrame() {
        @Override public dev.comfyfluffy.caustica.api.vulkan.GpuImage layer() { return null; }
        @Override public float[] worldViewProjection() { return new float[16]; }
        @Override public dev.comfyfluffy.caustica.api.view.SceneView view() { return null; }
        @Override public dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor entrySceneTlasDescriptor() { return null; }
        @Override public VkCommandBuffer commandBuffer() { return null; }
        @Override public void retain(dev.comfyfluffy.caustica.api.resource.ResourceOwner resource) { throw new AssertionError(); }
        @Override public long frameIndex() { return 1; }
        @Override public double timeSeconds() { return 2.0; }
        @Override public double metersPerSceneUnit() { return 1.0; }
        @Override public int renderWidth() { return 1920; }
        @Override public int renderHeight() { return 1080; }
    };
}
