package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProviderManagerTest {
    @Test
    void disablesOnlyTheFailingProvider() {
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update() {
                failedCalls.incrementAndGet();
                throw new IllegalStateException("expected");
            }

            @Override
            public void stop() {
                stopCalls.incrementAndGet();
            }

            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
            }
        };
        SceneProvider healthy = new SceneProvider() {
            @Override
            public void update() {
                healthyCalls.incrementAndGet();
            }
        };
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("failing"), failing);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());

        manager.updateScenes();
        manager.updateScenes();

        assertEquals(1, failedCalls.get());
        assertEquals(1, stopCalls.get());
        assertEquals(0, shutdownCalls.get());
        assertEquals(2, healthyCalls.get());

        shutdown(manager);
        assertEquals(1, shutdownCalls.get());
    }

    @Test
    void aProviderTornDownByFailureIsNotShutDownAgainAtSessionClose() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = counting(shutdowns, () -> {
            throw new IllegalStateException("expected");
        });
        ProviderManager manager = manager("failing", failing);

        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, shutdowns.get());
    }

    @Test
    void shutdownRunsOncePerProviderEvenIfCalledRepeatedly() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider healthy = counting(shutdowns, () -> {
        });
        ProviderManager manager = manager("healthy", healthy);

        shutdown(manager);
        shutdown(manager);

        assertEquals(1, shutdowns.get());
    }

    @Test
    void stopAndShutdownAreSeparateOrderedPhases() {
        List<String> events = new ArrayList<>();
        SceneProvider provider = new SceneProvider() {
            @Override
            public void stop() {
                events.add("stop");
            }

            @Override
            public void shutdown() {
                events.add("shutdown");
            }
        };
        ProviderManager manager = manager("phased", provider);

        manager.stopProviders();
        assertEquals(List.of("stop"), events);

        manager.shutdownResources();
        assertEquals(List.of("stop", "shutdown"), events);
    }

    @Test
    void aNormallyStoppedProviderRunsAgainInTheNextSession() {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider healthy = counting(shutdowns, updates::incrementAndGet);
        ProviderManager manager = manager("healthy", healthy);

        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);

        assertEquals(2, updates.get());
        assertEquals(2, shutdowns.get());
    }

    @Test
    void aFailedProviderRemainsDisabledAcrossSessions() {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = counting(shutdowns, () -> {
            updates.incrementAndGet();
            throw new IllegalStateException("expected");
        });
        ProviderManager manager = manager("failing", failing);

        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, updates.get());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void aProviderWhoseShutdownFailsRemainsDisabledAcrossSessions() {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update() {
                updates.incrementAndGet();
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
                throw new IllegalStateException("expected");
            }
        };
        ProviderManager manager = manager("shutdown_failure", failing);

        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, updates.get());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void aTornDownProviderNeverReceivesAnotherCallback() {
        AtomicInteger updates = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update() {
                updates.incrementAndGet();
                throw new IllegalStateException("expected");
            }
        };
        ProviderManager manager = manager("failing", failing);

        manager.updateScenes();
        manager.prepareFrame();
        manager.invalidateScenes();
        manager.updateScenes();

        assertEquals(1, updates.get());
    }

    @Test
    void lightSnapshotIsReplacedEveryFrameAndDropsRemovedLightsImmediately() {
        AtomicInteger frames = new AtomicInteger();
        LightProvider light = new LightProvider() {
            @Override
            public void submitLights(dev.comfyfluffy.caustica.api.provider.LightSink sink) {
                if (frames.getAndIncrement() == 0) {
                    sink.submit(new LightDescriptor.Point(1, 2, 3, 4,
                            8, 20, 20, 20));
                }
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(id("light"), light), Map.of());

        manager.prepareFrame();
        assertEquals(1, manager.frameLights().size());
        manager.prepareFrame();
        assertEquals(List.of(), manager.frameLights());
    }

    @Test
    void invalidProviderDoesNotPublishItsPartialSnapshot() {
        LightProvider invalid = new LightProvider() {
            @Override
            public void submitLights(dev.comfyfluffy.caustica.api.provider.LightSink sink) {
                sink.submit(new LightDescriptor.Point(1, 0, 0, 0,
                        3, 1, 1, 1));
                sink.submit(new LightDescriptor.Spot(2, 0, 0, 0,
                        0, 0, 0, 3, 0.5, 1, 1, 1));
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(id("invalid"), invalid), Map.of());

        manager.prepareFrame();

        assertEquals(List.of(), manager.frameLights());
    }

    @Test
    void duplicateLightKeysRejectOnlyThatProviderTransactionally() {
        LightProvider duplicate = new LightProvider() {
            @Override
            public void submitLights(dev.comfyfluffy.caustica.api.provider.LightSink sink) {
                sink.submit(new LightDescriptor.Point(4, 0, 0, 0, 3, 1, 1, 1));
                sink.submit(new LightDescriptor.Distant(4, 0, 1, 0, 100, 100, 100, 0));
            }
        };
        LightProvider independent = new LightProvider() {
            @Override
            public void submitLights(dev.comfyfluffy.caustica.api.provider.LightSink sink) {
                sink.submit(new LightDescriptor.Distant(4, 0, 1, 0, 10, 10, 10, 0));
            }
        };
        Map<ResourceId, LightProvider> providers = new LinkedHashMap<>();
        providers.put(id("duplicate"), duplicate);
        providers.put(id("independent"), independent);
        ProviderManager manager = new ProviderManager(Map.of(), providers, Map.of());

        manager.prepareFrame();

        assertEquals(1, manager.frameLights().size());
        assertEquals(4, manager.frameLights().getFirst().key());
        assertEquals(LightDescriptor.Distant.class, manager.frameLights().getFirst().getClass());
    }

    @Test
    void geometryCollectionIsTransactionalAndProviderScoped() {
        TriangleMesh mesh = triangle();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void submitGeometry(SceneGeometrySink sink) {
                sink.retainMesh(1, mesh);
                throw new IllegalStateException("expected");
            }
        };
        SceneProvider first = geometryProvider(mesh);
        SceneProvider second = geometryProvider(mesh);
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("failing_geometry"), failing);
        scenes.put(id("first_geometry"), first);
        scenes.put(id("second_geometry"), second);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());
        Map<ResourceId, List<String>> submitted = new LinkedHashMap<>();

        manager.submitGeometry(provider -> new SceneGeometrySink() {
            @Override
            public void retainMesh(long key, TriangleMesh value) {
                submitted.computeIfAbsent(provider, ignored -> new ArrayList<>()).add("mesh:" + key);
            }

            @Override
            public void instance(long key, long meshKey, GeometryTransform transform) {
                submitted.computeIfAbsent(provider, ignored -> new ArrayList<>()).add("instance:" + key);
            }
        });

        assertEquals(Map.of(
                id("first_geometry"), List.of("mesh:1", "instance:2"),
                id("second_geometry"), List.of("mesh:1", "instance:2")), submitted);
    }

    @Test
    void materialCollectionIsTransactionalAndIsolatesFailingSources() {
        MaterialRule discarded = rule("discarded");
        MaterialRule retained = rule("retained");
        AtomicInteger failingStops = new AtomicInteger();
        MaterialSource failing = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.submit(discarded);
                throw new IllegalStateException("expected");
            }

            @Override
            public void stop() {
                failingStops.incrementAndGet();
            }
        };
        MaterialSource healthy = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.submit(retained);
            }
        };
        Map<ResourceId, MaterialSource> materials = new LinkedHashMap<>();
        materials.put(id("failing"), failing);
        materials.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), materials);

        assertEquals(List.of(retained), manager.collectMaterials().rules());
        assertEquals(List.of(retained), manager.collectMaterials().rules());
        assertEquals(1, failingStops.get());
    }

    @Test
    void duplicateNamedMaterialDisablesOnlyTheLaterSource() {
        MaterialDefinition shared = definition("shared");
        AtomicInteger duplicateStops = new AtomicInteger();
        MaterialSource first = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.define(shared);
            }
        };
        MaterialSource duplicate = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.define(shared);
            }

            @Override
            public void stop() {
                duplicateStops.incrementAndGet();
            }
        };
        Map<ResourceId, MaterialSource> materials = new LinkedHashMap<>();
        materials.put(id("first"), first);
        materials.put(id("duplicate"), duplicate);
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), materials);

        assertEquals(List.of(shared), manager.collectMaterials().definitions());
        assertEquals(1, duplicateStops.get());
    }

    private static SceneProvider counting(AtomicInteger shutdowns, Runnable update) {
        return new SceneProvider() {
            @Override
            public void update() {
                update.run();
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
            }
        };
    }

    private static void shutdown(ProviderManager manager) {
        manager.stopProviders();
        manager.shutdownResources();
    }

    private static ProviderManager manager(String path, SceneProvider provider) {
        return new ProviderManager(Map.of(id(path), provider), Map.of(), Map.of());
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }

    private static MaterialRule rule(String path) {
        return new MaterialRule(id(path), new MaterialRule.Match(id(path), null),
                new MaterialRule.Parameters(null, null, null, null, null, null));
    }

    private static SceneProvider geometryProvider(TriangleMesh mesh) {
        return new SceneProvider() {
            @Override
            public void submitGeometry(SceneGeometrySink sink) {
                sink.retainMesh(1, mesh);
                sink.instance(2, 1, GeometryTransform.translation(0, 0, 0));
            }
        };
    }

    private static TriangleMesh triangle() {
        return new TriangleMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[6],
                new int[]{0, 1, 2}, List.of(new TriangleMesh.MaterialRange(0, 1,
                MaterialHandle.of("test", "geometry"))));
    }

    private static MaterialDefinition definition(String path) {
        return new MaterialDefinition(new MaterialHandle(id(path)), 1.0f, 1.0f, 1.0f,
                1.0f, 0.0f, 1.5f, 0.0f, null);
    }
}
