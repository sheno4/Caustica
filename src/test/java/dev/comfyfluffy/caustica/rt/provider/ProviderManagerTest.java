package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.RetainedLightCollection;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.geometry.GeometryUpdates;
import dev.comfyfluffy.caustica.rt.texture.ProviderTextureRegistry;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void rendererLightWorkerStopsBeforeSceneProducer() {
        List<String> events = new ArrayList<>();
        SceneProvider provider = new SceneProvider() {
            @Override
            public void stop() {
                events.add("provider");
            }
        };
        ProviderManager manager = manager("phased", provider);
        manager.bindRetainedLightStop(() -> events.add("retained-lights"));

        manager.stopProviders();

        assertEquals(List.of("retained-lights", "provider"), events);
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
    void aFailedProviderIsRetriedInTheNextSession() {
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

        assertEquals(2, updates.get());
        assertEquals(2, shutdowns.get());
    }

    @Test
    void aProviderWhoseShutdownFailsIsRetriedInTheNextSession() {
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

        assertEquals(2, updates.get());
        assertEquals(2, shutdowns.get());
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
        manager.onWorldChanged();
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
    void retainedLightGroupsAreSourceQualifiedAndReuseUnchangedAggregate() {
        LightProvider first = retained(7L, 3L, 10.0);
        LightProvider second = retained(7L, 5L, 20.0);
        Map<ResourceId, LightProvider> providers = new LinkedHashMap<>();
        providers.put(id("first"), first);
        providers.put(id("second"), second);
        ProviderManager manager = new ProviderManager(Map.of(), providers, Map.of());

        manager.prepareFrame();
        var initial = manager.retainedLights();
        manager.prepareFrame();

        assertSame(initial, manager.retainedLights());
        assertEquals(2, initial.batches().size());
        assertEquals(id("first"), initial.batches().get(0).source());
        assertEquals(id("second"), initial.batches().get(1).source());
        assertEquals(7L, initial.batches().get(0).key());
    }

    @Test
    void retainedLightRevisionAndOmissionReplaceOnlyThatProvidersGroups() {
        AtomicInteger frame = new AtomicInteger();
        LightProvider changing = new LightProvider() {
            @Override
            public RetainedLightCollection retainedLights() {
                int current = frame.getAndIncrement();
                return current < 2
                        ? collection(current + 1L, 1L, current + 1L, point(1L, current + 1.0))
                        : new RetainedLightCollection(3L, List.of());
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(id("changing"), changing), Map.of());

        manager.prepareFrame();
        long firstGeneration = manager.retainedLights().generation();
        manager.prepareFrame();
        assertTrue(manager.retainedLights().generation() > firstGeneration);
        assertEquals(2L, manager.retainedLights().batches().getFirst().revision());
        manager.prepareFrame();
        assertTrue(manager.retainedLights().isEmpty());
    }

    @Test
    void failingRetainedLightProviderCannotPublishPartialGroups() {
        AtomicInteger stops = new AtomicInteger();
        LightProvider broken = new LightProvider() {
            @Override
            public RetainedLightCollection retainedLights() {
                return new RetainedLightCollection(1L, List.of(
                        new RetainedLightCollection.Group(1L, 1L, List.of(point(1L, 1.0))),
                        new RetainedLightCollection.Group(2L, 1L, List.of(new LightDescriptor.Spot(
                                2L, 0, 0, 0, 0, 0, 0, 3, 0.5, 1, 1, 1)))));
            }

            @Override
            public void stop() {
                stops.incrementAndGet();
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(id("broken"), broken), Map.of());

        manager.prepareFrame();

        assertTrue(manager.retainedLights().isEmpty());
        assertEquals(1, stops.get());
    }

    @Test
    void unchangedRetainedCollectionSkipsGroupWalkAndLightValidation() {
        AtomicReference<RetainedLightCollection> collection = new AtomicReference<>(
                collection(7L, 1L, 3L, point(1L, 2.0)));
        LightProvider provider = new LightProvider() {
            @Override
            public RetainedLightCollection retainedLights() {
                return collection.get();
            }
        };
        AtomicInteger validations = new AtomicInteger();
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(id("retained"), provider), Map.of(),
                ignored -> validations.incrementAndGet());

        manager.prepareFrame();
        var published = manager.retainedLights();
        manager.prepareFrame();

        assertEquals(1, validations.get());
        assertSame(published, manager.retainedLights());

        collection.set(collection(8L, 1L, 4L, point(1L, 3.0)));
        manager.prepareFrame();
        assertEquals(2, validations.get());
        assertEquals(4L, manager.retainedLights().batches().getFirst().revision());
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
    void materialSourcesContributeIndependentAssetCalibration() {
        var atlas = asset("atlas", dev.comfyfluffy.caustica.engine.material.MaterialTextureKind.SHARED_ATLAS,
                12_000.0f);
        var standalone = asset("standalone", dev.comfyfluffy.caustica.engine.material.MaterialTextureKind.STANDALONE,
                24_000.0f);
        Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
        sources.put(id("minecraft"), sink -> sink.submitAsset(atlas));
        sources.put(id("gltf"), sink -> sink.submitAsset(standalone));

        ProviderManager.MaterialContributions contributions =
                new ProviderManager(Map.of(), Map.of(), sources).collectMaterials(ignored -> 0);

        assertEquals(List.of(atlas), contributions.catalog().atlasAssets());
        assertEquals(List.of(standalone), contributions.catalog().standalone());
        assertEquals(12_000.0f, contributions.catalog().atlasAssets().getFirst()
                .uniformEmissionLuminanceCdM2());
        assertEquals(24_000.0f, contributions.catalog().standalone().getFirst()
                .uniformEmissionLuminanceCdM2());
    }

    @Test
    void duplicateAssetDisablesOnlyTheLaterSourceWithoutPublishingItsOtherAssets() {
        var shared = asset("shared", dev.comfyfluffy.caustica.engine.material.MaterialTextureKind.SHARED_ATLAS, 1.0f);
        var discarded = asset("discarded", dev.comfyfluffy.caustica.engine.material.MaterialTextureKind.STANDALONE,
                2.0f);
        AtomicInteger duplicateStops = new AtomicInteger();
        MaterialSource duplicate = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.submitAsset(discarded);
                sink.submitAsset(shared);
            }

            @Override
            public void stop() {
                duplicateStops.incrementAndGet();
            }
        };
        Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
        sources.put(id("first"), sink -> sink.submitAsset(shared));
        sources.put(id("duplicate"), duplicate);

        var catalog = new ProviderManager(Map.of(), Map.of(), sources)
                .collectMaterials(ignored -> 0).catalog();

        assertEquals(List.of(shared), catalog.atlasAssets());
        assertTrue(catalog.standalone().isEmpty());
        assertEquals(1, duplicateStops.get());
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
    void everySceneProviderCanContributeSourceLocalTextures() {
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("first_primary"), new TextureSceneProvider());
        scenes.put(id("second_primary"), new TextureSceneProvider());
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());
        AtomicInteger destroyed = new AtomicInteger();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(4,
                (texture, label) -> uploaded(100L, destroyed), (slot, imageView, layout) -> { });

        manager.bindTextureRegistry(registry);

        assertEquals(2, registry.size());
        manager.unbindTextureRegistry(registry);
        registry.close();
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

    @Test
    void failingGeometryCallbackForwardsNothingAndDisablesOnlyThatProvider() {
        AtomicInteger failingStops = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                sink.submit(1, List.of(new SceneGeometrySink.Drop(1)));
                throw new IllegalStateException("expected");
            }

            @Override
            public void stop() {
                failingStops.incrementAndGet();
            }
        };
        SceneProvider healthy = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                healthyCalls.incrementAndGet();
                sink.submit(2, List.of(new SceneGeometrySink.Drop(2)));
            }
        };
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("broken"), failing);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> forwarded.addAll(updates));
        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> forwarded.addAll(updates));

        assertEquals(List.of(id("healthy"), id("healthy")), forwarded.stream()
                .map(update -> update.key().source()).toList());
        assertEquals(1, failingStops.get());
        assertEquals(2, healthyCalls.get());
    }

    @Test
    void invalidGeometryMaterialDisablesOnlyItsProviderBeforeForwarding() {
        AtomicInteger brokenStops = new AtomicInteger();
        SceneProvider broken = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                sink.submit(1, List.of(new SceneGeometrySink.Put(1, triangle("missing"))));
            }

            @Override
            public void stop() {
                brokenStops.incrementAndGet();
            }
        };
        SceneProvider healthy = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                sink.submit(2, List.of(new SceneGeometrySink.Drop(2)));
            }
        };
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("broken"), broken);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(),
                Map.of(id("materials"), defining("healthy")));
        manager.collectMaterials(ignored -> 0);
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> forwarded.addAll(updates));

        assertEquals(List.of(id("healthy")), forwarded.stream().map(update -> update.key().source()).toList());
        assertEquals(1, brokenStops.get());
    }

    @Test
    void catalogGeometryDoesNotRequireAPublicMaterialDefinition() {
        SceneProvider terrain = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                frame.geometry().submit(1, List.of(new SceneGeometrySink.Put(1, catalogTriangle())));
            }
        };
        ProviderManager manager = manager("terrain", terrain);
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> forwarded.addAll(updates));

        assertEquals(1, forwarded.size());
        assertEquals(id("terrain"), forwarded.getFirst().key().source());
    }

    @Test
    void frameIndexFlowsIntoSceneProviderContext() {
        AtomicReference<SceneFrameContext> captured = new AtomicReference<>();
        ProviderManager manager = manager("scene", new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                captured.set(frame);
            }
        });
        manager.bindSceneGeometry(new RtSceneGeometryManager((material, coverage) -> null));

        manager.submitGeometry(null, SceneOrigin.ZERO, 42L,
                dev.comfyfluffy.caustica.api.provider.SceneCamera.IDENTITY);

        assertEquals(42L, captured.get().frameIndex());
    }

    @Test
    void updateCadenceForwardsTerrainGroupsBeforeAnyFrameSubmission() {
        AtomicInteger frameSubmissions = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        SceneProvider terrain = new SceneProvider() {
            @Override
            public void submitGeometryUpdates(dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext update) {
                update.geometry().submit(1, List.of(new SceneGeometrySink.Drop(1)), ignored -> publications.incrementAndGet());
            }

            @Override
            public void submitGeometry(SceneFrameContext frame) {
                frameSubmissions.incrementAndGet();
            }
        };
        ProviderManager manager = manager("terrain", terrain);
        RtSceneGeometryManager sceneGeometry = new RtSceneGeometryManager((material, coverage) -> null);
        manager.bindSceneGeometry(sceneGeometry);

        manager.submitGeometryUpdates(null, SceneOrigin.ZERO);
        sceneGeometry.progress(null);
        sceneGeometry.progress(null);

        assertEquals(1, publications.get());
        assertEquals(0, frameSubmissions.get());
    }

    @Test
    void geometryGroupsReceiveMonotonicRevisionsAndRebasedPlacements() {
        SceneOrigin origin = new SceneOrigin(30_000_000, 64, -30_000_000);
        SceneProvider provider = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                sink.submit(7, List.of(
                        new SceneGeometrySink.Place(9, 3,
                                GeometryTransform.translation(30_000_000.25, 65.5, -29_999_999.75), 0x3f),
                        new SceneGeometrySink.Transform(SceneGeometryKey.of(9),
                                GeometryTransform.translation(30_000_001.25, 66.5, -29_999_998.75), 0x7f)));
            }
        };
        ProviderManager manager = manager("geometry", provider);
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, origin, (updates, ignored) -> forwarded.addAll(updates));
        manager.submitGeometry(null, origin, (updates, ignored) -> forwarded.addAll(updates));

        assertEquals(List.of(1L, 2L), forwarded.stream().map(GeometryUpdates.Group::revision).toList());
        GeometryUpdates.Place place = (GeometryUpdates.Place) forwarded.getFirst().operations().getFirst();
        assertEquals(origin, place.origin());
        assertEquals(0.25f, place.transform()[3]);
        assertEquals(1.5f, place.transform()[7]);
        assertEquals(0.25f, place.transform()[11]);
        assertEquals(0x3f, place.mask());
        GeometryUpdates.UpdatePlacement transform =
                (GeometryUpdates.UpdatePlacement) forwarded.getFirst().operations().get(1);
        assertEquals(origin, transform.origin());
        assertEquals(1.25f, transform.transform()[3]);
        assertEquals(2.5f, transform.transform()[7]);
        assertEquals(1.25f, transform.transform()[11]);
        assertEquals(0x7f, transform.mask());
    }

    @Test
    void unpublishedCloudMovementKeepsMeshAndPlacementInOneProviderGroup() {
        AtomicInteger cell = new AtomicInteger();
        SceneProvider cloud = new SceneProvider() {
            private boolean meshesPublished;

            @Override
            public void submitGeometry(SceneFrameContext frame) {
                List<SceneGeometrySink.Operation> operations = new ArrayList<>();
                if (!meshesPublished) operations.add(new SceneGeometrySink.Put(0, triangle("cloud")));
                operations.add(new SceneGeometrySink.Place(7, 0,
                        GeometryTransform.translation(cell.get() * 128.0, 192.0, 0.0)));
                frame.geometry().submit(0, operations, ignored -> meshesPublished = true);
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(id("cloud"), cloud), Map.of(),
                Map.of(id("materials"), defining("cloud")));
        manager.collectMaterials(ignored -> 0);
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> forwarded.addAll(updates));
        cell.incrementAndGet();
        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> forwarded.addAll(updates));

        assertEquals(List.of(1L, 2L), forwarded.stream().map(GeometryUpdates.Group::revision).toList());
        for (GeometryUpdates.Group update : forwarded) {
            assertTrue(update.operations().stream().anyMatch(GeometryUpdates.Put.class::isInstance));
            assertTrue(update.operations().stream().anyMatch(GeometryUpdates.Place.class::isInstance));
        }
    }

    @Test
    void asynchronousGeometryFailureDisablesOnlyItsSource() {
        AtomicInteger brokenStops = new AtomicInteger();
        SceneProvider broken = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                sink.submit(1, List.of(new SceneGeometrySink.Drop(1)));
            }

            @Override
            public void stop() {
                brokenStops.incrementAndGet();
            }
        };
        AtomicInteger healthySubmits = new AtomicInteger();
        SceneProvider healthy = new SceneProvider() {
            @Override
            public void submitGeometry(SceneFrameContext frame) {
                SceneGeometrySink sink = frame.geometry();
                healthySubmits.incrementAndGet();
                sink.submit(2, List.of(new SceneGeometrySink.Drop(2)));
            }
        };
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("broken"), broken);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());
        AtomicReference<java.util.function.Consumer<Throwable>> failure = new AtomicReference<>();

        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, handler) -> {
            if (updates.getFirst().key().source().equals(id("broken"))) failure.set(handler);
        });
        assertTrue(failure.get() != null);
        failure.get().accept(new IllegalStateException("expected"));
        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, ignored) -> assertEquals(id("healthy"),
                updates.getFirst().key().source()));

        assertEquals(1, brokenStops.get());
        assertEquals(2, healthySubmits.get());
    }

    @Test
    void materialEpochClearFlowsThroughSceneProvider() {
        TextureSceneProvider scene = new TextureSceneProvider();
        ProviderManager manager = manager("primary", scene);

        manager.clearMaterials();

        assertEquals(1, scene.materialClears);
    }

    @Test
    void textureCollectionFlowsThroughSceneProvider() {
        TextureSceneProvider scene = new TextureSceneProvider();
        ProviderManager manager = manager("primary", scene);
        AtomicInteger destroyed = new AtomicInteger();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(3,
                (texture, label) -> uploaded(100L, destroyed), (slot, imageView, layout) -> { });

        manager.bindTextureRegistry(registry);

        assertEquals(1, scene.textureSubmissions);
        assertEquals(1, registry.size());
        manager.unbindTextureRegistry(registry);
        registry.close();
    }

    @Test
    void failedTextureProviderDoesNotConsumeHealthyProviderCapacity() {
        AtomicInteger failedRetired = new AtomicInteger();
        AtomicInteger failedStops = new AtomicInteger();
        SceneProvider broken = new SceneProvider() {
            @Override
            public void submitTextures(dev.comfyfluffy.caustica.api.provider.TextureSink textures) {
                textures.submit(new SceneMesh.StandaloneTexture(id("broken")),
                        new dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture(301L,
                                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, failedRetired::incrementAndGet));
                throw new IllegalStateException("expected");
            }

            @Override
            public void stop() {
                failedStops.incrementAndGet();
            }
        };
        TextureSceneProvider healthy = new TextureSceneProvider();
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("broken"), broken);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2,
                (texture, label) -> uploaded(100L, new AtomicInteger()), (slot, imageView, layout) -> { });

        manager.bindTextureRegistry(registry);

        assertEquals(1, failedRetired.get());
        assertEquals(1, failedStops.get());
        assertEquals(1, registry.size());
        assertEquals(1, registry.requireSlot(id("healthy"),
                new SceneMesh.StandaloneTexture(id("shared"))));
        manager.unbindTextureRegistry(registry);
        registry.close();
    }

    private static ProviderManager manager(String path, SceneProvider provider) {
        return new ProviderManager(Map.of(id(path), provider), Map.of(), Map.of());
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }

    private static LightProvider retained(long key, long revision, double x) {
        return new LightProvider() {
            @Override
            public RetainedLightCollection retainedLights() {
                return collection(revision, key, revision, point(key, x));
            }
        };
    }

    private static RetainedLightCollection collection(long generation, long key, long revision,
                                                      LightDescriptor.Finite light) {
        return new RetainedLightCollection(generation, List.of(
                new RetainedLightCollection.Group(key, revision, List.of(light))));
    }

    private static LightDescriptor.Point point(long key, double x) {
        return new LightDescriptor.Point(key, x, 0.0, 0.0, 3.0, 1.0, 1.0, 1.0);
    }

    private static MaterialRule rule(String path) {
        return new MaterialRule(id(path), new MaterialRule.Match(id(path), null),
                new MaterialRule.Parameters(null, null, null, null, null, null));
    }

    private static MaterialSource defining(String path) {
        return sink -> sink.define(definition(path));
    }

    private static MaterialDefinition definition(String path) {
        return new MaterialDefinition(new MaterialHandle(id(path)), 1.0f, 1.0f, 1.0f,
                1.0f, 0.0f, 1.5f, 0.0f, MaterialTopology.SURFACE, null);
    }

    private static dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset asset(
            String path, dev.comfyfluffy.caustica.engine.material.MaterialTextureKind kind, float luminance) {
        return new dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset(id(path), kind, 1, 1,
                () -> null, dev.comfyfluffy.caustica.engine.material.MaterialUv.IDENTITY,
                false, false, false,
                dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding.PARAMETER_DEFAULT,
                dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding.PARAMETER_DEFAULT,
                dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR,
                luminance);
    }

    private static SceneMesh triangle(String material) {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], List.of(
                SceneMesh.TriangleSurface.surface(MaterialHandle.of("test", material))));
    }

    private static SceneMesh catalogTriangle() {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], List.of(new SceneMesh.TriangleSurface(
                new SceneMesh.CatalogMaterial(ResourceId.of("minecraft", "stone"), null,
                        new dev.comfyfluffy.caustica.engine.material.MaterialVariant(
                                dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile.ROUGH_DIELECTRIC,
                                MaterialTopology.SURFACE, false)),
                SceneMesh.Coverage.OPAQUE, Float.NaN, Float.NaN, Float.NaN, 0, 1, 1, 1)));
    }

    private static ProviderTextureRegistry.UploadedTexture uploaded(long view, AtomicInteger destroyed) {
        return new ProviderTextureRegistry.UploadedTexture() {
            @Override public long imageView() { return view; }
            @Override public int imageLayout() { return org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL; }
            @Override public void destroy() { destroyed.incrementAndGet(); }
        };
    }

    private static final class TextureSceneProvider implements SceneProvider {
        final AtomicInteger stops = new AtomicInteger();
        int textureSubmissions;
        int materialClears;

        @Override
        public void submitTextures(dev.comfyfluffy.caustica.api.provider.TextureSink textures) {
            textureSubmissions++;
            textures.submit(new SceneMesh.StandaloneTexture(id("shared")),
                    new dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture(
                            200L + textureSubmissions, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, () -> { }));
        }

        @Override
        public void onMaterialEpochClosing() {
            materialClears++;
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }
    }
}
