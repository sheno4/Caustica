package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        ProviderManager manager = new ProviderManager(scenes, Map.of(),
                Map.of(id("geometry_materials"), defining("geometry")));
        Map<ResourceId, List<String>> submitted = new LinkedHashMap<>();

        manager.collectMaterials(ignored -> 0);

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

        assertEquals(List.of(retained), manager.collectMaterials(ignored -> 0).rules());
        assertEquals(List.of(retained), manager.collectMaterials(ignored -> 0).rules());
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

        assertEquals(List.of(shared), manager.collectMaterials(ignored -> 0).definitions());
        assertEquals(1, duplicateStops.get());
    }

    @Test
    void unknownSurfaceDisablesOnlyItsMaterialSource() {
        AtomicInteger invalidStops = new AtomicInteger();
        MaterialDefinition invalid = new MaterialDefinition(new MaterialHandle(id("invalid")),
                1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.5f, 0.0f,
                MaterialTopology.SURFACE, id("missing_surface"));
        MaterialSource broken = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.define(invalid);
            }

            @Override
            public void stop() {
                invalidStops.incrementAndGet();
            }
        };
        MaterialDefinition healthy = definition("healthy");
        Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
        sources.put(id("broken"), broken);
        sources.put(id("healthy"), sink -> sink.define(healthy));
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), sources);

        ProviderManager.MaterialContributions contributions = manager.collectMaterials(surface -> -1);

        assertEquals(List.of(healthy), contributions.definitions());
        assertEquals(1, invalidStops.get());
    }

    @Test
    void unknownRuleSurfaceDisablesOnlyItsMaterialSource() {
        AtomicInteger invalidStops = new AtomicInteger();
        MaterialRule invalid = new MaterialRule(id("invalid_rule"),
                new MaterialRule.Match(id("source"), null),
                new MaterialRule.Parameters(null, null, null, null, null, id("missing_surface")));
        MaterialSource broken = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.submit(invalid);
            }

            @Override
            public void stop() {
                invalidStops.incrementAndGet();
            }
        };
        MaterialRule healthy = rule("healthy_rule");
        Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
        sources.put(id("broken"), broken);
        sources.put(id("healthy"), sink -> sink.submit(healthy));
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), sources);

        ProviderManager.MaterialContributions contributions = manager.collectMaterials(surface -> -1);

        assertEquals(List.of(healthy), contributions.rules());
        assertEquals(1, invalidStops.get());
    }

    @Test
    void unresolvedGeometryMaterialDisablesOnlyTheOffendingSceneBeforePublication() {
        TriangleMesh missing = triangle("missing");
        TriangleMesh healthy = triangle("healthy");
        AtomicInteger brokenStops = new AtomicInteger();
        SceneProvider broken = new SceneProvider() {
            @Override
            public void submitGeometry(SceneGeometrySink sink) {
                sink.retainMesh(1, missing);
                sink.instance(2, 1, GeometryTransform.translation(0, 0, 0));
            }

            @Override
            public void stop() {
                brokenStops.incrementAndGet();
            }
        };
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("broken_geometry"), broken);
        scenes.put(id("healthy_geometry"), geometryProvider(healthy));
        ProviderManager manager = new ProviderManager(scenes, Map.of(),
                Map.of(id("materials"), defining("healthy")));
        List<ResourceId> published = new ArrayList<>();

        manager.collectMaterials(ignored -> 0);
        manager.submitGeometry(provider -> new SceneGeometrySink() {
            @Override
            public void retainMesh(long key, TriangleMesh mesh) {
                published.add(provider);
            }

            @Override
            public void instance(long key, long meshKey, GeometryTransform transform) {
            }
        });

        assertEquals(List.of(id("healthy_geometry")), published);
        assertEquals(1, brokenStops.get());
        published.clear();
        manager.submitGeometry(provider -> new SceneGeometrySink() {
            @Override
            public void retainMesh(long key, TriangleMesh mesh) {
                published.add(provider);
            }

            @Override
            public void instance(long key, long meshKey, GeometryTransform transform) {
            }
        });
        assertEquals(List.of(id("healthy_geometry")), published);
    }

    @Test
    void optimizedSceneSelectionDelegatesWithoutCopyingFrameProducts() {
        OptimizedProvider source = new OptimizedProvider();
        ProviderManager manager = manager("optimized", source);

        ProviderManager.PrimaryScene selected = manager.primaryScene();
        assertEquals(id("optimized"), selected.provider());
        assertSame(source.retained, selected.retained());
        assertEquals(37, manager.bindlessTextureCapacity());

        manager.resetBindlessTextures(64);
        manager.uploadPendingTextures(null, 91L);
        RtSceneSource.Frame frame = manager.beginPrimaryFrame(selected, null, List.of(),
                source.retained.geometryTable(),
                new RtSceneSource.Camera(1, 2, 3, new Matrix4f(), new Matrix4f()));
        frame.markGraphicsUse(null);

        assertSame(source.frame, frame);
        assertEquals(64, source.resetCapacity);
        assertEquals(91L, source.uploadSampler);
        assertEquals(1, source.frameMarks.get());

        manager.stopProviders();
        assertNull(manager.primaryScene());
        assertEquals(1, manager.bindlessTextureCapacity());
    }

    @Test
    void rejectsMultipleActiveOptimizedSceneSources() {
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("first_optimized"), new OptimizedProvider());
        scenes.put(id("second_optimized"), new OptimizedProvider());
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());

        assertThrows(IllegalStateException.class, manager::primaryScene);
        assertThrows(IllegalStateException.class, manager::bindlessTextureCapacity);
    }

    @Test
    void failingOptimizedFrameStopsAndDisablesItsProvider() {
        OptimizedProvider source = new OptimizedProvider();
        ProviderManager manager = manager("failing_optimized", source);
        ProviderManager.PrimaryScene selected = manager.primaryScene();
        source.failFrame = true;

        assertThrows(IllegalStateException.class, () -> manager.beginPrimaryFrame(selected, null,
                List.of(), source.retained.geometryTable(),
                new RtSceneSource.Camera(0, 0, 0, new Matrix4f(), new Matrix4f())));

        assertEquals(1, source.stops.get());
        assertNull(manager.primaryScene());
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
        return triangle("geometry");
    }

    private static TriangleMesh triangle(String material) {
        return new TriangleMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[6],
                new int[]{0, 1, 2}, List.of(new TriangleMesh.MaterialRange(0, 1,
                MaterialHandle.of("test", material))));
    }

    private static MaterialSource defining(String path) {
        return sink -> sink.define(definition(path));
    }

    private static MaterialDefinition definition(String path) {
        return new MaterialDefinition(new MaterialHandle(id(path)), 1.0f, 1.0f, 1.0f,
                1.0f, 0.0f, 1.5f, 0.0f, MaterialTopology.SURFACE, null);
    }

    private static final class OptimizedProvider implements SceneProvider, RtSceneSource {
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger frameMarks = new AtomicInteger();
        final Retained retained = new Retained(SceneOrigin.ZERO, List.of(),
                new RtGeometryAbi.TablePrefix(1L, 0),
                new RetainedLights(0, 0, -1, 0, 0,
                        0, 0, 0, 1, 0));
        final Frame frame = new Frame() {
            @Override
            public List<RtAccel.Instance> dynamicInstances() {
                return List.of();
            }

            @Override
            public List<RtAccel.PreparedBlas> blasBuilds() {
                return List.of();
            }

            @Override
            public long geometryTableAddress() {
                return 1L;
            }

            @Override
            public void markGraphicsUse(GraphicsUse graphicsUse) {
                frameMarks.incrementAndGet();
            }
        };
        boolean failFrame;
        int resetCapacity;
        long uploadSampler;

        @Override
        public Retained retainedScene() {
            return retained;
        }

        @Override
        public Frame beginFrame(GpuContext ctx, Retained retained, List<RtAccel.Instance> baseInstances,
                                RtGeometryAbi.TablePrefix geometryTable, Camera camera) {
            if (failFrame) {
                throw new IllegalStateException("expected");
            }
            return frame;
        }

        @Override
        public int bindlessTextureCapacity() {
            return 37;
        }

        @Override
        public void resetBindlessTextures(int capacity) {
            resetCapacity = capacity;
        }

        @Override
        public void uploadPendingTextures(RtPipeline pipeline, long sampler) {
            uploadSampler = sampler;
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }
    }
}
