package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramContributionChannel;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProgramSessionTest {
    interface Implementation { }
    interface Binding { }
    interface Instance { }
    interface EnvironmentBinding { }

    private static final ShaderDataType<Implementation> IMPLEMENTATION = ShaderDataType.create("implementation");
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final ShaderDataType<Instance> INSTANCE = ShaderDataType.create("instance");
    private static final ShaderDataType<EnvironmentBinding> ENVIRONMENT_BINDING =
            ShaderDataType.create("environment binding");

    @Test
    void registrationIsAtomicTypedAndPublishesBeforeReadyCallback() {
        ManualBackend backend = new ManualBackend();
        List<Throwable> failures = new ArrayList<>();
        ProgramSession session = new ProgramSession(resources(), backend, failures::add);
        ProgramContributionChannel channel = session.openChannel(new ContributionOwner(1));
        List<String> events = new ArrayList<>();

        record Exports(SurfaceId<Binding, Instance> surface,
                       VolumeId<Binding, Instance> volume,
                       EnvironmentId<EnvironmentBinding> environment) { }
        ProgramRegistration<Exports> registration = channel.register(builder -> new Exports(
                builder.surface(surface("sample.Surface", () -> events.add("surface-retired"))),
                builder.volume(volume("sample.Volume", () -> events.add("volume-retired"))),
                builder.environment(new EnvironmentDefinition<>(
                        shader("environment", "sample.Environment"), ENVIRONMENT_BINDING))));
        registration.whenComplete(completion -> {
            assertInstanceOf(ProgramRegistration.Ready.class, completion);
            assertTrue(backend.active != null);
            events.add("ready");
        });

        assertTrue(events.isEmpty());
        assertEquals(0, session.resolve(registration.exports().surface()));
        session.progress();
        assertEquals(3, backend.pending.composition.declarations().size());
        assertThrows(UnsupportedOperationException.class,
                () -> backend.pending.composition.declarations().clear());
        backend.succeed();
        session.progress();

        assertEquals(List.of("ready"), events);
        assertEquals(1, session.resolve(registration.exports().surface()));
        assertEquals(1, session.resolve(registration.exports().volume()));
        assertEquals(1, session.resolve(registration.exports().environment()));
        assertTrue(failures.isEmpty());

        AtomicInteger lateCallbacks = new AtomicInteger();
        registration.whenComplete(ignored -> lateCallbacks.incrementAndGet());
        assertEquals(0, lateCallbacks.get(), "completed registrations must not invoke callbacks inline");
        session.progress();
        assertEquals(1, lateCallbacks.get());
    }

    @Test
    void failedRegistrationPublishesNothingAndLaterRegistrationUsesLastGoodBase() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(resources(), backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(new ContributionOwner(1));
        List<String> retired = new ArrayList<>();
        ProgramRegistration<SurfaceId<Binding, Instance>> first = channel.register(
                builder -> builder.surface(surface("sample.First", () -> retired.add("first"))));
        ProgramRegistration<SurfaceId<Binding, Instance>> broken = channel.register(
                builder -> builder.surface(surface("sample.Broken", () -> retired.add("broken"))));
        ProgramRegistration<SurfaceId<Binding, Instance>> later = channel.register(
                builder -> builder.surface(surface("sample.Later", () -> retired.add("later"))));
        List<ProgramRegistration.Completion> firstCompletion = new ArrayList<>();
        List<ProgramRegistration.Completion> brokenCompletion = new ArrayList<>();
        List<ProgramRegistration.Completion> laterCompletion = new ArrayList<>();
        first.whenComplete(firstCompletion::add);
        broken.whenComplete(brokenCompletion::add);
        later.whenComplete(laterCompletion::add);

        session.progress();
        backend.succeed();
        session.progress();
        assertInstanceOf(ProgramRegistration.Ready.class, firstCompletion.getFirst());
        assertEquals(2, backend.pendingRegistrationCount());

        backend.fail("broken shader");
        session.progress();
        ProgramRegistration.Failed failed = assertInstanceOf(
                ProgramRegistration.Failed.class, brokenCompletion.getFirst());
        assertEquals("broken shader", failed.failure().summary());
        assertTrue(retired.isEmpty());
        assertEquals(0, session.resolve(broken.exports()));
        assertEquals(1, session.resolve(first.exports()));
        assertEquals(2, backend.pendingRegistrationCount(),
                "the later candidate must exclude the failed registration");

        backend.succeed();
        session.progress();
        assertInstanceOf(ProgramRegistration.Ready.class, laterCompletion.getFirst());
        assertEquals(2, backend.activeComposition.declarations().size());
        assertEquals(3, session.resolve(later.exports()));
    }

    @Test
    void removingAnotherOwnerDoesNotRetargetRetainedProgramSlots() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(resources(), backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel removableOwner = session.openChannel(new ContributionOwner(1));
        ProgramContributionChannel geometryOwner = session.openChannel(new ContributionOwner(2));

        record Exports(SurfaceId<Binding, Instance> surface, VolumeId<Binding, Instance> volume) { }
        ProgramRegistration<Exports> removable = removableOwner.register(builder -> new Exports(
                builder.surface(surface("sample.RemovableSurface", () -> { })),
                builder.volume(volume("sample.RemovableVolume", () -> { }))));
        ProgramRegistration<Exports> retained = geometryOwner.register(builder -> new Exports(
                builder.surface(surface("sample.RetainedSurface", () -> { })),
                builder.volume(volume("sample.RetainedVolume", () -> { }))));
        session.progress();
        backend.succeed();
        session.progress();
        backend.succeed();
        session.progress();

        int retainedSurfaceSlot = session.resolve(retained.exports().surface());
        int retainedVolumeSlot = session.resolve(retained.exports().volume());
        assertEquals(2, retainedSurfaceSlot);
        assertEquals(2, retainedVolumeSlot);

        removable.close();
        session.progress();
        backend.succeed();
        session.progress();

        assertEquals(0, session.resolve(removable.exports().surface()));
        assertEquals(0, session.resolve(removable.exports().volume()));
        assertEquals(retainedSurfaceSlot, session.resolve(retained.exports().surface()));
        assertEquals(retainedVolumeSlot, session.resolve(retained.exports().volume()));

        ProgramRegistration<Exports> replacement = removableOwner.register(builder -> new Exports(
                builder.surface(surface("sample.ReplacementSurface", () -> { })),
                builder.volume(volume("sample.ReplacementVolume", () -> { }))));
        session.progress();
        backend.succeed();
        session.progress();
        assertEquals(3, session.resolve(replacement.exports().surface()));
        assertEquals(3, session.resolve(replacement.exports().volume()));
    }

    @Test
    void closeLinearizesOnEitherSideOfPublicationAndRetiresAfterDisplacedProgram() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(resources(), backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(new ContributionOwner(1));
        List<String> retired = new ArrayList<>();

        ProgramRegistration<SurfaceId<Binding, Instance>> cancelled = channel.register(
                builder -> builder.surface(surface("sample.Cancelled", () -> retired.add("cancelled"))));
        List<ProgramRegistration.Completion> cancelledCompletion = new ArrayList<>();
        cancelled.whenComplete(cancelledCompletion::add);
        session.progress();
        backend.succeed();
        cancelled.close();
        assertTrue(cancelledCompletion.isEmpty(), "close must not invoke callbacks inline");
        assertFalse(session.isDrained(channel), "the stale compiler completion still belongs to this owner");
        session.progress();
        assertInstanceOf(ProgramRegistration.Cancelled.class, cancelledCompletion.getFirst());
        assertEquals(1, backend.closedCandidates);
        assertTrue(retired.isEmpty());
        assertTrue(session.isDrained(channel));
        assertEquals(0, session.resolve(cancelled.exports()));

        ProgramRegistration<SurfaceId<Binding, Instance>> ready = channel.register(
                builder -> builder.surface(surface("sample.Ready", () -> retired.add("ready"))));
        List<ProgramRegistration.Completion> readyCompletion = new ArrayList<>();
        ready.whenComplete(readyCompletion::add);
        session.progress();
        backend.succeed();
        session.progress();
        assertInstanceOf(ProgramRegistration.Ready.class, readyCompletion.getFirst());
        ready.close();
        assertEquals(2, session.resolve(ready.exports()));

        session.progress();
        backend.succeed();
        session.progress();
        assertEquals(0, session.resolve(ready.exports()));
        assertTrue(retired.isEmpty());
        backend.retireLatestPrevious();
        session.progress();
        assertTrue(retired.isEmpty());
    }

    @Test
    void ownerDrainForcesPublishedUseRetirementAfterRemovalPublication() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(resources(), backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(new ContributionOwner(1));
        AtomicInteger retired = new AtomicInteger();
        channel.register(builder -> builder.surface(surface("sample.Ready", retired::incrementAndGet)));
        session.progress();
        backend.succeed();
        session.progress();

        channel.invalidate();
        session.progress();
        backend.succeed();
        channel.drain();

        assertEquals(0, retired.get());
        assertTrue(backend.publishedUseDrains > 0);
        assertTrue(session.isDrained(channel));
    }

    @Test
    void declarationsSerializeAcrossThreadsAndTypeConflictTakesNoRetirementOwnership() throws Exception {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(resources(), backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel first = session.openChannel(new ContributionOwner(1));
        ProgramContributionChannel second = session.openChannel(new ContributionOwner(2));
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread one = Thread.ofPlatform().start(() -> first.register(builder -> {
            maximum.accumulateAndGet(inside.incrementAndGet(), Math::max);
            entered.countDown();
            await(release);
            inside.decrementAndGet();
            return builder.surface(surface("sample.One", () -> { }));
        }));
        entered.await();
        Thread two = Thread.ofPlatform().start(() -> second.register(builder -> {
            maximum.accumulateAndGet(inside.incrementAndGet(), Math::max);
            inside.decrementAndGet();
            return builder.surface(surface("sample.Two", () -> { }));
        }));
        release.countDown();
        one.join();
        two.join();
        assertEquals(1, maximum.get());

        AtomicInteger notOwned = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> first.register(builder -> builder.surface(
                new SurfaceDefinition<>(shader("different_module", "sample.One"), null,
                        IMPLEMENTATION.data(0), BINDING, INSTANCE))));
        session.progress();
        assertEquals(0, notOwned.get());
    }

    @Test
    void ownerInvalidationCancelsPendingSetsAndRejectsNewDeclarations() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(resources(), backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(new ContributionOwner(1));
        AtomicInteger retired = new AtomicInteger();
        ProgramRegistration<?> registration = channel.register(
                builder -> builder.surface(surface("sample.Pending", retired::incrementAndGet)));
        List<ProgramRegistration.Completion> completion = new ArrayList<>();
        registration.whenComplete(completion::add);

        channel.invalidate();

        assertTrue(completion.isEmpty(), "invalidation must not invoke callbacks inline");
        assertFalse(session.isDrained(channel));
        assertThrows(IllegalStateException.class, () -> channel.register(builder -> "late"));
        session.progress();
        assertInstanceOf(ProgramRegistration.Cancelled.class, completion.getFirst());
        assertEquals(0, retired.get());
        assertTrue(session.isDrained(channel));
    }

    @Test
    void callbackFailureDoesNotBlockLaterTicketOrRetirementCallbacks() {
        ManualBackend backend = new ManualBackend();
        List<Throwable> failures = new ArrayList<>();
        ProgramSession session = new ProgramSession(resources(), backend, failures::add);
        ProgramContributionChannel channel = session.openChannel(new ContributionOwner(1));
        List<String> callbacks = new ArrayList<>();
        ProgramRegistration<?> registration = channel.register(builder -> {
            builder.surface(surface("sample.Throwing", () -> {
                throw new IllegalStateException("retirement");
            }));
            return builder.volume(volume("sample.Following", () -> callbacks.add("retirement")));
        });
        registration.whenComplete(ignored -> {
            throw new IllegalStateException("readiness");
        });
        registration.whenComplete(ignored -> callbacks.add("registration"));

        session.progress();
        backend.fail("compile");
        session.progress();

        assertEquals(List.of("registration"), callbacks);
        assertEquals(List.of("readiness"),
                failures.stream().map(Throwable::getMessage).toList());
        assertTrue(session.isDrained(channel));
    }

    @Test
    void implementationResourcesRejectReleasedAndForeignDeviceReferencesAndRollBackAcquisitions() {
        ResourceDirectory resources = resources();
        ContributionOwner owner = new ContributionOwner(1);
        ProgramSession session = new ProgramSession(
                resources, new ManualBackend(), failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(owner);
        var resourceChannel = resources.openFactory(owner);
        AtomicInteger firstRetired = new AtomicInteger();
        var first = resourceChannel.create(firstRetired::incrementAndGet);

        var released = resourceChannel.create();
        released.close();

        assertThrows(IllegalStateException.class, () -> channel.register(builder -> {
            builder.surface(new SurfaceDefinition<>(shader("first", "sample.ResourceFirst"), null,
                    IMPLEMENTATION.data(1, first.reference()), BINDING, INSTANCE));
            return builder.volume(new VolumeDefinition<>(shader("second", "sample.ResourceSecond"),
                    IMPLEMENTATION.data(2, released.reference()), BINDING, INSTANCE));
        }));
        first.close();
        resources.awaitRetirements();
        assertEquals(1, firstRetired.get(), "a rejected declaration must release earlier acquisitions");

        ResourceDirectory foreignDirectory = resources();
        var foreignSession = foreignDirectory.openFactory(owner).create();

        assertThrows(IllegalArgumentException.class, () -> channel.register(builder -> builder.surface(
                new SurfaceDefinition<>(shader("foreign_session", "sample.ForeignSession"), null,
                        IMPLEMENTATION.data(4, foreignSession.reference()),
                        BINDING, INSTANCE))));
    }

    @Test
    void sharedImplementationGenerationRetiresAfterPublishedProgramUseAndDefinitionCallbacks() {
        ResourceDirectory resources = resources();
        ContributionOwner owner = new ContributionOwner(1);
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(
                resources, backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(owner);
        List<String> events = new ArrayList<>();
        var generation = resources.openFactory(owner).create(() -> events.add("resource"));


        ProgramRegistration<?> registration = channel.register(builder -> {
            builder.surface(new SurfaceDefinition<>(shader("surface_root", "sample.ResourceSurface"), null,
                    IMPLEMENTATION.data(1, generation.reference()), BINDING, INSTANCE));
            return builder.volume(new VolumeDefinition<>(shader("volume_root", "sample.ResourceVolume"),
                    IMPLEMENTATION.data(2, generation.reference()), BINDING, INSTANCE));
        });
        generation.close();
        resources.awaitRetirements();
        assertTrue(events.isEmpty());

        session.progress();
        backend.succeed();
        session.progress();
        registration.close();
        session.progress();
        backend.succeed();
        session.progress();
        resources.awaitRetirements();
        assertTrue(events.isEmpty(), "published use still owns the implementation generation");

        backend.retireLatestPrevious();
        session.progress();
        resources.awaitRetirements();
        assertEquals(List.of("resource"), events);
    }

    @Test
    void failedAndCancelledCompilationsHoldImplementationGenerationsUntilCompletion() {
        ResourceDirectory resources = resources();
        ContributionOwner owner = new ContributionOwner(1);
        ManualBackend backend = new ManualBackend();
        ProgramSession session = new ProgramSession(
                resources, backend, failure -> { throw new AssertionError(failure); });
        ProgramContributionChannel channel = session.openChannel(owner);
        List<String> retired = new ArrayList<>();

        var failedRoot = resources.openFactory(owner).create(() -> retired.add("failed-resource"));

        channel.register(builder -> builder.surface(new SurfaceDefinition<>(
                shader("failed_root", "sample.FailedResource"), null,
                IMPLEMENTATION.data(1, failedRoot.reference()), BINDING, INSTANCE)));
        failedRoot.close();
        session.progress();
        backend.fail("failed");
        resources.awaitRetirements();
        assertTrue(retired.isEmpty());
        session.progress();
        resources.awaitRetirements();
        assertEquals(List.of("failed-resource"), retired);

        var cancelledRoot = resources.openFactory(owner).create(() -> retired.add("cancelled-resource"));

        ProgramRegistration<?> cancelled = channel.register(builder -> builder.surface(new SurfaceDefinition<>(
                shader("cancelled_root", "sample.CancelledResource"), null,
                IMPLEMENTATION.data(2, cancelledRoot.reference()), BINDING, INSTANCE)));
        cancelledRoot.close();
        session.progress();
        cancelled.close();
        resources.awaitRetirements();
        assertEquals(List.of("failed-resource"), retired,
                "cancellation must retain resources while its compile is in flight");
        backend.succeed();
        session.progress();
        resources.awaitRetirements();
        assertEquals(List.of("failed-resource", "cancelled-resource"), retired);
    }

    private static SurfaceDefinition<Binding, Instance> surface(
            String type, Runnable retired) {
        return new SurfaceDefinition<>(shader(type.substring(type.lastIndexOf('.') + 1).toLowerCase(), type),
                null, IMPLEMENTATION.data(1), BINDING, INSTANCE);
    }

    private static VolumeDefinition<Binding, Instance> volume(String type, Runnable retired) {
        return new VolumeDefinition<>(shader("volume", type), IMPLEMENTATION.data(2), BINDING, INSTANCE);
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(ShaderSource.classpath(ProgramSessionTest.class, "/shaders"), module, type);
    }

    private static ResourceDirectory resources() {
        return new ResourceDirectory(failure -> { throw new AssertionError(failure); });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class ManualBackend implements ProgramBackend {
        private Pending pending;
        private CompiledProgram active;
        private ProgramComposition activeComposition;
        private final List<ProgramComposition> requested = new ArrayList<>();
        private final List<Runnable> previousRetirements = new ArrayList<>();
        private int closedCandidates;
        private int publishedUseDrains;

        @Override
        public void compile(ProgramComposition composition,
                            java.util.function.Consumer<? super Compilation> completion) {
            if (pending != null) throw new AssertionError("only one compile may be in flight");
            requested.add(composition);
            pending = new Pending(composition, completion);
        }

        @Override
        public void publish(CompiledProgram program, Runnable previousRetired) {
            active = program;
            activeComposition = ((TestProgram) program).composition;
            previousRetirements.add(previousRetired);
        }

        @Override
        public void drainPublishedUses() {
            publishedUseDrains++;
            while (!previousRetirements.isEmpty()) previousRetirements.removeFirst().run();
        }

        void succeed() {
            Pending value = pending;
            pending = null;
            value.completion.accept(new Compilation.Succeeded(new TestProgram(value.composition)));
        }

        void fail(String summary) {
            Pending value = pending;
            pending = null;
            value.completion.accept(new Compilation.Failed(new ProgramFailure(summary, "diagnostics")));
        }

        void retireLatestPrevious() {
            previousRetirements.removeLast().run();
        }

        int pendingRegistrationCount() {
            return pending.composition.declarations().size();
        }

        private record Pending(ProgramComposition composition,
                               java.util.function.Consumer<? super Compilation> completion) { }

        private final class TestProgram implements CompiledProgram {
            private final ProgramComposition composition;
            private boolean closed;

            private TestProgram(ProgramComposition composition) {
                this.composition = composition;
            }

            @Override public void close() {
                if (closed) return;
                closed = true;
                closedCandidates++;
            }
        }
    }
}
