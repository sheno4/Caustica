package dev.comfyfluffy.caustica.minecraft.adapter.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.engine.session.ContributionScope;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftWorldSessionHostTest {
    private static final dev.comfyfluffy.caustica.settings.OptionLookup OPTIONS = id -> { throw new AssertionError(id); };
    @Test
    void borrowsOneWorldEpochAndOwnsDistinctCoreScopesThroughFullLifecycle() {
        List<String> events = new ArrayList<>();
        List<TestScope> scopes = new ArrayList<>();
        List<MinecraftSessionFailure> failures = new ArrayList<>();
        List<EnvironmentBinding<?>> selected = new ArrayList<>();
        List<ContributionOwner> environmentOwners = new ArrayList<>();
        SceneId scene = new SceneId() { };
        MinecraftDimensionKey dimension = MinecraftDimensionKey.of("minecraft", "overworld");
        MinecraftWorldSessionHost host = new MinecraftWorldSessionHost(OPTIONS);
        assertSame(OPTIONS, host.api().options());

        host.api().sessions().add(context -> {
            assertSame(scene, context.scene());
            assertEquals(dimension, context.dimension());
            assertEquals(new ResourcePackEpoch(4), context.resourcePackEpoch());
            assertSame(scopes.get(0).program, context.renderSession().program());
            var type = ShaderDataType.<Object>create("environment test");
            context.environment().select(EnvironmentBinding.of(new EnvironmentId<>() { }, type.data(7)));
            return contribution("one", events, false);
        });
        host.api().sessions().add(context -> {
            assertSame(scopes.get(1).program, context.renderSession().program());
            var type = ShaderDataType.<Object>create("second environment test");
            context.environment().select(EnvironmentBinding.of(new EnvironmentId<>() { }, type.data(8)));
            return contribution("two", events, true);
        });

        MinecraftWorldSession session = host.openSession(owner -> {
            TestScope scope = new TestScope(Long.toString(owner.sequence()), events);
            scopes.add(scope);
            return scope;
        }, (owner, borrowedScene) -> {
            environmentOwners.add(owner);
            return environmentScope(selected);
        },
                scene, dimension, new ResourcePackEpoch(4), failures::add);
        session.processPendingChanges();

        assertEquals(2, session.contributionCount());
        assertNotSame(scopes.get(0).program, scopes.get(1).program);
        assertEquals(2, selected.size());
        assertEquals(List.of(1L, 2L), environmentOwners.stream().map(ContributionOwner::sequence).toList());
        session.resourcePackChanged(new ResourcePackEpoch(5));
        assertEquals(List.of("one:pack:5", "two:pack:5"), events);
        assertEquals(List.of(MinecraftSessionFailure.Stage.RESOURCE_PACK_CHANGED),
                failures.stream().map(MinecraftSessionFailure::stage).toList());
        assertThrows(IllegalArgumentException.class,
                () -> session.resourcePackChanged(new ResourcePackEpoch(5)));

        events.clear();
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
    void registrationRemovalAndFailedOpenDrainOnlyTheirOwnedScope() {
        List<String> events = new ArrayList<>();
        List<MinecraftSessionFailure.Stage> failures = new ArrayList<>();
        MinecraftWorldSessionHost host = new MinecraftWorldSessionHost(OPTIONS);
        var registration = host.api().sessions().add(context -> contribution("live", events, false));
        host.api().sessions().add(context -> {
            throw new IllegalStateException("open failed");
        });

        MinecraftWorldSession session = host.openSession(
                owner -> new TestScope(Long.toString(owner.sequence()), events),
                (owner, borrowedScene) -> environmentScope(new ArrayList<>()),
                new SceneId() { }, MinecraftDimensionKey.of("minecraft", "the_end"),
                new ResourcePackEpoch(0), failure -> failures.add(failure.stage()));
        session.processPendingChanges();
        assertEquals(1, session.contributionCount());
        assertEquals(List.of(MinecraftSessionFailure.Stage.OPEN_CONTRIBUTION), failures);
        assertEquals(List.of("2:quiesce", "2:invalidate", "2:drain", "2:scope-close"), events);

        events.clear();
        registration.close();
        session.processPendingChanges();
        assertEquals(0, session.contributionCount());
        assertEquals(List.of("1:quiesce", "live:stop", "1:invalidate", "1:drain",
                "live:close", "1:scope-close"), events);
    }

    @Test
    void compositeCloseCanSettleSceneWorkBetweenInvalidationAndOwnerDrain() {
        List<String> events = new ArrayList<>();
        MinecraftWorldSessionHost host = new MinecraftWorldSessionHost(OPTIONS);
        host.api().sessions().add(context -> contribution("live", events, false));
        MinecraftWorldSession session = host.openSession(
                owner -> new TestScope(Long.toString(owner.sequence()), events),
                (owner, borrowedScene) -> environmentScope(new ArrayList<>()),
                new SceneId() { }, MinecraftDimensionKey.of("minecraft", "overworld"),
                new ResourcePackEpoch(0), failure -> { throw new AssertionError(failure); });
        session.processPendingChanges();

        session.beginClose();
        events.add("scene:settle");
        session.finishClose();

        assertEquals(List.of("1:quiesce", "live:stop", "1:invalidate", "scene:settle",
                "1:drain", "live:close", "1:scope-close"), events);
    }

    private static MinecraftWorldSessionContribution contribution(
            String name, List<String> events, boolean failPack) {
        return new MinecraftWorldSessionContribution() {
            @Override public void resourcePackChanged(ResourcePackEpoch epoch) {
                events.add(name + ":pack:" + epoch.generation());
                if (failPack) throw new IllegalStateException("pack callback failed");
            }
            @Override public void stop() { events.add(name + ":stop"); }
            @Override public void close() { events.add(name + ":close"); }
        };
    }

    private static MinecraftEnvironmentScope environmentScope(List<EnvironmentBinding<?>> selected) {
        return new MinecraftEnvironmentScope() {
            @Override public void select(EnvironmentBinding<?> binding) { selected.add(binding); }
            @Override public void invalidate() { }
            @Override public void drain() { }
        };
    }

    private static final class TestScope implements ContributionScope {
        private final String id;
        private final List<String> events;
        private final ProgramChannel program = service(ProgramChannel.class);
        private final PassChannel passes = service(PassChannel.class);
        private final GeometryChannel geometry = service(GeometryChannel.class);
        private final LightChannel lights = service(LightChannel.class);
        private final dev.comfyfluffy.caustica.api.resource.ResourceFactory resources =
                service(dev.comfyfluffy.caustica.api.resource.ResourceFactory.class);

        private TestScope(String id, List<String> events) { this.id = id; this.events = events; }
        @Override public GpuDevice gpu() { return service(GpuDevice.class); }
        @Override public ProgramChannel program() { return program; }
        @Override public PassChannel passes() { return passes; }
        @Override public GeometryChannel geometry() { return geometry; }
        @Override public LightChannel lights() { return lights; }
        @Override public dev.comfyfluffy.caustica.api.resource.ResourceFactory resources() { return resources; }
        @Override public void quiesce() { events.add(id + ":quiesce"); }
        @Override public void invalidate() { events.add(id + ":invalidate"); }
        @Override public void drain() { events.add(id + ":drain"); }
        @Override public void close() { events.add(id + ":scope-close"); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T service(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> null);
    }
}
