package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.api.session.RenderSessionRegistration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EngineRenderSessionTest {
    @Test
    void failedFactoryIsNotRetriedWhenItRegistersAnotherFactory() {
        var events = new ArrayList<String>();
        var failures = new ArrayList<SessionFailure>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var host = new RenderSessionHost()) {
            host.api().sessions().add(context -> {
                if (attempts.incrementAndGet() == 1) {
                    host.api().sessions().add(next -> contribution("nested", events));
                }
                throw new IllegalStateException("broken extension");
            });
            try (var session = host.openSession(owner -> new TestScope("scope", events), failures::add)) {
                session.processPendingChanges();
                assertEquals(1, attempts.get());
                assertEquals(1, failures.size());
                assertEquals(1, session.contributionCount());
                host.api().sessions().add(context -> contribution("later", events));
                session.processPendingChanges();
                assertEquals(1, attempts.get());
                assertEquals(1, failures.size());
                assertEquals(2, session.contributionCount());
            }
        }
    }

    @Test
    void registrationsCreatedWhileOpeningAreReconciledBeforeReturning() {
        List<String> events = new ArrayList<>();
        try (var host = new RenderSessionHost()) {
            host.api().sessions().add(context -> {
                events.add("open:first");
                host.api().sessions().add(next -> {
                    events.add("open:second");
                    return contribution("second", events);
                });
                return contribution("first", events);
            });
            try (var session = host.openSession(owner -> new TestScope("scope", events),
                    failure -> { throw new AssertionError(failure); })) {
                session.processPendingChanges();
                session.processPendingChanges();
                assertEquals(List.of("open:first", "open:second"), events);
                assertEquals(2, session.contributionCount());
            }
        }
    }

    @Test
    void opensFreshOwnerScopesAndTearsDownInGlobalPhases() {
        List<String> events = new ArrayList<>();
        List<TestScope> created = new ArrayList<>();
        RenderSessionHost host = new RenderSessionHost();
        host.api().sessions().add(context -> {
            assertSame(created.get(0).program, context.program());
            return contribution("one", events);
        });
        host.api().sessions().add(context -> {
            assertSame(created.get(1).geometry, context.meshes());
            return contribution("two", events);
        });

        EngineRenderSession session = host.openSession(owner -> {
            TestScope scope = new TestScope(Long.toString(owner.sequence()), events);
            created.add(scope);
            return scope;
        }, failure -> events.add("failure:" + failure.stage()));
        session.processPendingChanges();

        assertEquals(2, session.contributionCount());
        session.close();
        assertEquals(List.of(
                "1:quiesce", "2:quiesce",
                "one:stop", "two:stop",
                "1:invalidate", "2:invalidate",
                "1:drain", "2:drain",
                "one:close", "two:close",
                "1:scope-close", "2:scope-close"), events);
    }

    @Test
    void registrationCloseQueuesOneFullyDrainedRemovalAndPreventsFutureOpen() {
        List<String> events = new ArrayList<>();
        RenderSessionHost host = new RenderSessionHost();
        RenderSessionRegistration registration = host.api().sessions().add(
                context -> contribution("owner", events));
        EngineRenderSession first = host.openSession(
                owner -> new TestScope("scope", events), failure -> events.add("failure"));
        first.processPendingChanges();

        registration.close();
        assertEquals(1, first.contributionCount());
        first.processPendingChanges();
        assertEquals(0, first.contributionCount());
        assertEquals(List.of("scope:quiesce", "owner:stop", "scope:invalidate", "scope:drain",
                "owner:close", "scope:scope-close"), events);

        EngineRenderSession second = host.openSession(
                owner -> new TestScope("unused", events), failure -> events.add("failure"));
        second.processPendingChanges();
        assertEquals(0, second.contributionCount());
        second.close();
        first.close();
    }

    @Test
    void failedFactoryCleansOnlyItsAcceptedScopeAndDoesNotRunContributionHooks() {
        List<String> events = new ArrayList<>();
        List<SessionFailure> failures = new ArrayList<>();
        RenderSessionHost host = new RenderSessionHost();
        host.api().sessions().add(context -> {
            throw new IllegalStateException("broken extension");
        });
        host.api().sessions().add(context -> contribution("good", events));
        EngineRenderSession session = host.openSession(
                owner -> new TestScope(Long.toString(owner.sequence()), events), failures::add);

        session.processPendingChanges();

        assertEquals(1, session.contributionCount());
        assertEquals(List.of(SessionFailure.Stage.OPEN_CONTRIBUTION),
                failures.stream().map(SessionFailure::stage).toList());
        assertEquals(List.of("1:quiesce", "1:invalidate", "1:drain", "1:scope-close"), events);
        session.close();
    }

    @Test
    void teardownFailuresAreReportedWithoutSkippingLaterPhasesOrOwners() {
        List<String> events = new ArrayList<>();
        List<SessionFailure> failures = new ArrayList<>();
        RenderSessionHost host = new RenderSessionHost();
        host.api().sessions().add(context -> new RenderSessionContribution() {
            @Override public void stop() { throw new IllegalStateException("stop"); }
            @Override public void close() { events.add("contribution:close"); }
        });
        EngineRenderSession session = host.openSession(owner -> new TestScope("scope", events) {
            @Override public void invalidate() { throw new IllegalStateException("invalidate"); }
        }, failures::add);
        session.processPendingChanges();

        session.close();

        assertEquals(List.of(SessionFailure.Stage.STOP_CONTRIBUTION, SessionFailure.Stage.INVALIDATE),
                failures.stream().map(SessionFailure::stage).toList());
        assertEquals(List.of("scope:quiesce", "scope:drain", "contribution:close", "scope:scope-close"),
                events);
        assertThrows(IllegalStateException.class, session::processPendingChanges);
    }

    private static RenderSessionContribution contribution(String name, List<String> events) {
        return new RenderSessionContribution() {
            @Override public void stop() { events.add(name + ":stop"); }
            @Override public void close() { events.add(name + ":close"); }
        };
    }

    private static class TestScope implements ContributionScope {
        private final String name;
        private final List<String> events;
        private final GpuDevice gpu = stub(GpuDevice.class);
        private final dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue compute =
                stub(dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue.class);
        private final ProgramChannel program = stub(ProgramChannel.class);
        private final PassChannel passes = stub(PassChannel.class);
        private final MeshPreparer geometry = stub(MeshPreparer.class);
        private final SceneChannel lights = stub(SceneChannel.class);
        private final dev.comfyfluffy.caustica.api.resource.ResourceFactory resources =
                stub(dev.comfyfluffy.caustica.api.resource.ResourceFactory.class);

        private TestScope(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override public GpuDevice gpu() { return gpu; }
        @Override public dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue compute() { return compute; }
        @Override public ProgramChannel program() { return program; }
        @Override public PassChannel passes() { return passes; }
        @Override public MeshPreparer meshes() { return geometry; }
        @Override public SceneChannel scene() { return lights; }
        @Override public dev.comfyfluffy.caustica.api.resource.ResourceFactory resources() { return resources; }
        @Override public void quiesce() { events.add(name + ":quiesce"); }
        @Override public void invalidate() { events.add(name + ":invalidate"); }
        @Override public void drain() { events.add(name + ":drain"); }
        @Override public void close() { events.add(name + ":scope-close"); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
