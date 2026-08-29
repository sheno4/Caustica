package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.RetainedLightCollection;
import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.settings.ResourceId;
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
    void resourceLifecycleCallbacksReachEveryProviderKindInMaterialSceneLightOrderBeforeCollection() {
        List<String> events = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean resourcesApplied =
                new java.util.concurrent.atomic.AtomicBoolean();
        MaterialSource material = new MaterialSource() {
            @Override public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                assertTrue(resourcesApplied.get());
                events.add("material.collect");
            }
            @Override public void onResourcePackClosing() {
                resourcesApplied.set(false);
                events.add("material.close");
            }
            @Override public void onResourcePackApplied() {
                resourcesApplied.set(true);
                events.add("material.apply");
            }
        };
        SceneProvider scene = new SceneProvider() {
            @Override public void onResourcePackClosing() { events.add("scene.close"); }
            @Override public void onResourcePackApplied() { events.add("scene.apply"); }
        };
        LightProvider light = new LightProvider() {
            @Override public void onResourcePackClosing() { events.add("light.close"); }
            @Override public void onResourcePackApplied() { events.add("light.apply"); }
        };
        ProviderManager manager = new ProviderManager(Map.of(id("scene"), scene),
                Map.of(id("light"), light), Map.of(id("material"), material));

        manager.onResourcePackClosing();
        manager.onResourcePackApplied();
        manager.collectMaterials();

        assertEquals(List.of(
                "material.close", "scene.close", "light.close",
                "material.apply", "scene.apply", "light.apply", "material.collect"), events);
    }

    @Test
    void lifecycleFailureDisablesOnlyTheFailingProvider() {
        AtomicInteger stops = new AtomicInteger();
        MaterialSource broken = new MaterialSource() {
            @Override public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) { }
            @Override public void onResourcePackApplied() { throw new IllegalStateException("expected"); }
            @Override public void stop() { stops.incrementAndGet(); }
        };
        AtomicInteger sceneCallbacks = new AtomicInteger();
        SceneProvider healthy = new SceneProvider() {
            @Override public void onResourcePackApplied() { sceneCallbacks.incrementAndGet(); }
        };
        ProviderManager manager = new ProviderManager(Map.of(id("scene"), healthy), Map.of(),
                Map.of(id("material"), broken));

        manager.onResourcePackApplied();
        manager.onResourcePackApplied();

        assertEquals(1, stops.get());
        assertEquals(2, sceneCallbacks.get());
    }

    @Test
    void disablesOnlyTheFailingProvider() {
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update(SceneGeometryUpdateContext ignored) {
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
            public void update(SceneGeometryUpdateContext ignored) {
                healthyCalls.incrementAndGet();
            }
        };
        Map<ResourceId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("failing"), failing);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());

        manager.updateScenes(null, SceneOrigin.ZERO);
        manager.updateScenes(null, SceneOrigin.ZERO);

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

        manager.updateScenes(null, SceneOrigin.ZERO);
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
        manager.updateScenes(null, SceneOrigin.ZERO);
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes(null, SceneOrigin.ZERO);
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
        manager.updateScenes(null, SceneOrigin.ZERO);
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes(null, SceneOrigin.ZERO);
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
            public void update(SceneGeometryUpdateContext ignored) {
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
        manager.updateScenes(null, SceneOrigin.ZERO);
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes(null, SceneOrigin.ZERO);
        shutdown(manager);

        assertEquals(2, updates.get());
        assertEquals(2, shutdowns.get());
    }

    @Test
    void aTornDownProviderNeverReceivesAnotherCallback() {
        AtomicInteger updates = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update(SceneGeometryUpdateContext ignored) {
                updates.incrementAndGet();
                throw new IllegalStateException("expected");
            }
        };
        ProviderManager manager = manager("failing", failing);

        manager.updateScenes(null, SceneOrigin.ZERO);
        manager.prepareFrame();
        manager.updateScenes(null, SceneOrigin.ZERO);

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
    void unchangedRetainedCollectionKeepsPublishedSnapshot() {
        AtomicReference<RetainedLightCollection> collection = new AtomicReference<>(
                collection(7L, 1L, 3L, point(1L, 2.0)));
        LightProvider provider = new LightProvider() {
            @Override
            public RetainedLightCollection retainedLights() {
                return collection.get();
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(id("retained"), provider), Map.of());

        manager.prepareFrame();
        var published = manager.retainedLights();
        manager.prepareFrame();

        assertSame(published, manager.retainedLights());

        collection.set(collection(8L, 1L, 4L, point(1L, 3.0)));
        manager.prepareFrame();
        assertEquals(4L, manager.retainedLights().batches().getFirst().revision());
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
        MaterialDefinition discarded = definition("discarded");
        MaterialDefinition retained = definition("retained");
        AtomicInteger failingStops = new AtomicInteger();
        MaterialSource failing = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.define(discarded);
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
                sink.define(retained);
            }
        };
        Map<ResourceId, MaterialSource> materials = new LinkedHashMap<>();
        materials.put(id("failing"), failing);
        materials.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), materials);

        assertEquals(List.of(retained), manager.collectMaterials().definitions());
        assertEquals(List.of(retained), manager.collectMaterials().definitions());
        assertEquals(1, failingStops.get());
    }

    @Test
    void duplicateNamedMaterialDisablesOnlyTheLaterSource() {
        MaterialDefinition shared = definition("shared");
        AtomicInteger duplicateCommits = new AtomicInteger();
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
                sink.onCommit(duplicateCommits::incrementAndGet);
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
        assertEquals(0, duplicateCommits.get());
        assertEquals(1, duplicateStops.get());
    }

    @Test
    void materialCommitHookRunsAfterTextureMaterializationAndAcceptedDefinitionPublication() {
        List<String> events = new ArrayList<>();
        MaterialDefinition accepted = definition("accepted");
        MaterialSource source = sink -> {
            sink.register(new dev.comfyfluffy.caustica.api.provider.CpuTextureResource(1, 1,
                    dev.comfyfluffy.caustica.api.provider.CpuTextureResource.Encoding.SRGB,
                    new byte[] { 1, 2, 3, 4 }));
            sink.define(accepted);
            sink.onCommit(() -> {
                assertEquals(List.of("upload", "descriptor"), events);
                events.add("callback");
            });
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), Map.of(id("source"), source));
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2, (texture, label) -> {
            events.add("upload");
            return uploaded(100L, new AtomicInteger());
        }, (slot, imageView, layout) -> events.add("descriptor"));
        events.clear();
        manager.bindTextureRegistry(registry);

        ProviderManager.MaterialContributions contributions = manager.collectMaterials();

        assertEquals(List.of(accepted), contributions.definitions());
        assertEquals(List.of("upload", "descriptor", "callback"), events);
        manager.unbindTextureRegistry(registry);
        registry.close();
    }

    @Test
    void throwingMaterialCommitHookFailsTheEpochWithoutDisablingItsSource() {
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger laterSubmissions = new AtomicInteger();
        MaterialSource source = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.define(definition("committed"));
                sink.onCommit(() -> {
                    throw new IllegalStateException("commit hook failed");
                });
            }

            @Override
            public void stop() {
                stops.incrementAndGet();
            }
        };
        Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
        sources.put(id("source"), source);
        sources.put(id("later"), sink -> laterSubmissions.incrementAndGet());
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), sources);

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::collectMaterials);

        assertEquals("commit hook failed", failure.getMessage());
        assertEquals(0, stops.get());
        assertEquals(0, laterSubmissions.get());
    }

    @Test
    void unknownSurfaceKeepsItsMaterialDefinitionForVisibleErrorResolution() {
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

        ProviderManager.MaterialContributions contributions = manager.collectMaterials();

        assertEquals(List.of(invalid, healthy), contributions.definitions());
        assertEquals(0, invalidStops.get());
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
            public void update(SceneGeometryUpdateContext ignored) {
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

        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));
        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));

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
        manager.collectMaterials();
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));

        assertEquals(List.of(id("healthy")), forwarded.stream().map(update -> update.key().source()).toList());
        assertEquals(1, brokenStops.get());
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
        manager.bindSceneGeometry(new RtSceneGeometryManager(ignored -> (material, coverage) -> null));

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
            public void update(dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext update) {
                update.geometry().submit(1, List.of(new SceneGeometrySink.Drop(1)), publications::incrementAndGet);
            }

            @Override
            public void submitGeometry(SceneFrameContext frame) {
                frameSubmissions.incrementAndGet();
            }
        };
        ProviderManager manager = manager("terrain", terrain);
        RtSceneGeometryManager sceneGeometry = new RtSceneGeometryManager(ignored -> (material, coverage) -> null);
        manager.bindSceneGeometry(sceneGeometry);

        manager.updateScenes(null, SceneOrigin.ZERO);
        sceneGeometry.progress(null);
        sceneGeometry.progress(null);

        assertEquals(1, publications.get());
        assertEquals(0, frameSubmissions.get());
    }

    @Test
    void geometryCadenceSelectsRendererBuildPolicy() {
        SceneProvider provider = new SceneProvider() {
            @Override
            public void update(SceneGeometryUpdateContext update) {
                update.geometry().submit(1, List.of(new SceneGeometrySink.Put(1, fallbackTriangle())));
            }

            @Override
            public void submitGeometry(SceneFrameContext frame) {
                frame.geometry().submit(2, List.of(new SceneGeometrySink.Put(2, fallbackTriangle())));
            }
        };
        ProviderManager manager = manager("geometry", provider);
        List<GeometryUpdates.Group> updateCadence = new ArrayList<>();
        List<GeometryUpdates.Group> frameCadence = new ArrayList<>();

        manager.updateScenes(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> updateCadence.addAll(updates));
        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> frameCadence.addAll(updates));

        assertSame(GeometryUpdates.BuildPolicy.STATIC, buildPolicy(updateCadence.getFirst()));
        assertSame(GeometryUpdates.BuildPolicy.DYNAMIC, buildPolicy(frameCadence.getFirst()));
    }

    @Test
    void startsEachSceneProviderOncePerActivationAndClosesThePreviousScope() {
        List<SceneScope> scopes = new ArrayList<>();
        ProviderManager manager = manager("geometry", new SceneProvider() {
            @Override
            public void onSessionStart(SceneScope scope) {
                scopes.add(scope);
            }
        });

        manager.beginSession();
        manager.beginSession();

        assertEquals(2, scopes.size());
        assertThrows(IllegalStateException.class,
                () -> scopes.getFirst().submit(1, List.of(new SceneGeometrySink.Drop(1))));
        scopes.getLast().submit(1, List.of(new SceneGeometrySink.Drop(1)));
    }

    @Test
    void drainsCopiedCrossThreadScopeSubmissionsAtStaticCadence() throws InterruptedException {
        AtomicReference<SceneScope> scope = new AtomicReference<>();
        ProviderManager manager = manager("geometry", new SceneProvider() {
            @Override
            public void onSessionStart(SceneScope started) {
                scope.set(started);
            }
        });
        manager.beginSession();
        ArrayList<SceneGeometrySink.Operation> operations = new ArrayList<>();
        operations.add(new SceneGeometrySink.Put(1, fallbackTriangle()));

        Thread producer = new Thread(() -> scope.get().submit(7, operations));
        producer.start();
        producer.join();
        operations.clear();

        List<GeometryUpdates.Group> frameCadence = new ArrayList<>();
        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> frameCadence.addAll(updates));
        assertTrue(frameCadence.isEmpty());

        List<GeometryUpdates.Group> updateCadence = new ArrayList<>();
        manager.updateScenes(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> updateCadence.addAll(updates));
        assertEquals(1, updateCadence.size());
        assertSame(GeometryUpdates.BuildPolicy.STATIC, buildPolicy(updateCadence.getFirst()));
    }

    @Test
    void crossThreadSceneResetRequestsCoalesceGlobally() throws InterruptedException {
        List<SceneScope> scopes = new ArrayList<>();
        Map<ResourceId, SceneProvider> providers = new LinkedHashMap<>();
        providers.put(id("first"), new SceneProvider() {
            @Override public void onSessionStart(SceneScope scope) { scopes.add(scope); }
        });
        providers.put(id("second"), new SceneProvider() {
            @Override public void onSessionStart(SceneScope scope) { scopes.add(scope); }
        });
        ProviderManager manager = new ProviderManager(providers, Map.of(), Map.of());
        manager.beginSession();

        Thread first = new Thread(scopes.get(0)::requestSceneReset);
        Thread second = new Thread(scopes.get(1)::requestSceneReset);
        first.start();
        second.start();
        first.join();
        second.join();

        assertTrue(manager.consumeSceneResetRequest());
        assertTrue(!manager.consumeSceneResetRequest());
    }

    @Test
    void globalSceneResetDropsQueuedGroupsAndStaleAcknowledgmentsThenAcceptsTheNextGeneration() {
        Map<ResourceId, SceneScope> scopes = new LinkedHashMap<>();
        AtomicInteger providerUpdates = new AtomicInteger();
        Map<ResourceId, SceneProvider> providers = new LinkedHashMap<>();
        for (String path : List.of("first", "second")) {
            ResourceId source = id(path);
            providers.put(source, new SceneProvider() {
                @Override public void onSessionStart(SceneScope scope) { scopes.put(source, scope); }
                @Override public void update(SceneGeometryUpdateContext ignored) { providerUpdates.incrementAndGet(); }
            });
        }
        ProviderManager manager = new ProviderManager(providers, Map.of(), Map.of());
        manager.beginSession();
        AtomicInteger stalePublications = new AtomicInteger();
        scopes.forEach((source, scope) -> scope.submit(7,
                List.of(new SceneGeometrySink.Drop(1)), stalePublications::incrementAndGet));
        List<Runnable> acknowledgments = new ArrayList<>();
        manager.updateScenes(null, SceneOrigin.ZERO, (updates, acknowledgment, failure) -> {
            for (GeometryUpdates.Group update : updates) {
                acknowledgments.add(() -> acknowledgment.accept(new GeometryUpdates.Publication(
                        update.key(), update.revision(), update.operations())));
            }
        });
        scopes.values().forEach(scope -> scope.submit(8, List.of(new SceneGeometrySink.Drop(2))));
        int updatesBeforeReset = providerUpdates.get();

        scopes.values().iterator().next().requestSceneReset();
        assertTrue(manager.consumeSceneResetRequest());
        assertEquals(updatesBeforeReset, providerUpdates.get(),
                "reset consumption must not invoke providers");
        acknowledgments.forEach(Runnable::run);
        List<GeometryUpdates.Group> discarded = new ArrayList<>();
        manager.updateScenes(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> discarded.addAll(updates));

        assertEquals(0, stalePublications.get());
        assertTrue(discarded.isEmpty());
        assertEquals(updatesBeforeReset + providers.size(), providerUpdates.get(),
                "only the explicit post-reset update may invoke providers");

        scopes.forEach((source, scope) -> scope.submit(7, List.of(new SceneGeometrySink.Drop(3))));
        List<GeometryUpdates.Group> nextGeneration = new ArrayList<>();
        manager.updateScenes(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> nextGeneration.addAll(updates));
        assertEquals(providers.size(), nextGeneration.size());
        assertTrue(nextGeneration.stream().allMatch(group -> group.revision() == 1L));
    }

    @Test
    void closedSceneScopeDiscardsCrossThreadResetRequest() throws InterruptedException {
        AtomicReference<SceneScope> firstScope = new AtomicReference<>();
        ProviderManager manager = manager("geometry", new SceneProvider() {
            @Override public void onSessionStart(SceneScope scope) { firstScope.compareAndSet(null, scope); }
        });
        manager.beginSession();
        manager.beginSession();

        Thread request = new Thread(firstScope.get()::requestSceneReset);
        request.start();
        request.join();

        assertTrue(!manager.consumeSceneResetRequest());
    }

    @Test
    void globalSceneResetClearsFrameAndRetainedLightsAndTheirProviderGenerationCache() {
        AtomicReference<SceneScope> scope = new AtomicReference<>();
        SceneProvider scene = new SceneProvider() {
            @Override public void onSessionStart(SceneScope started) { scope.set(started); }
        };
        LightProvider light = new LightProvider() {
            @Override
            public void submitLights(dev.comfyfluffy.caustica.api.provider.LightSink sink) {
                sink.submit(new LightDescriptor.Distant(10L, 0.0, -1.0, 0.0, 1.0, 1.0, 1.0, 0.0));
            }

            @Override
            public RetainedLightCollection retainedLights() {
                return collection(3L, 7L, 5L, point(11L, 2.0));
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(id("scene"), scene), Map.of(id("light"), light),
                Map.of());
        manager.beginSession();
        manager.prepareFrame();
        long publishedGeneration = manager.retainedLights().generation();
        assertEquals(1, manager.frameLights().size());
        assertEquals(1, manager.retainedLights().batches().size());

        scope.get().requestSceneReset();
        assertTrue(manager.consumeSceneResetRequest());

        assertTrue(manager.frameLights().isEmpty());
        assertTrue(manager.retainedLights().isEmpty());
        assertTrue(manager.retainedLights().generation() > publishedGeneration);
        manager.prepareFrame();
        assertEquals(1, manager.frameLights().size());
        assertEquals(1, manager.retainedLights().batches().size(),
                "clearing the provider generation cache must accept the same generation again");
    }

    @Test
    void repeatedScopeGroupSubmissionsReceiveMonotonicRevisions() {
        AtomicReference<SceneScope> scope = new AtomicReference<>();
        ProviderManager manager = manager("geometry", new SceneProvider() {
            @Override
            public void onSessionStart(SceneScope started) {
                scope.set(started);
            }
        });
        manager.beginSession();
        scope.get().submit(7, List.of(new SceneGeometrySink.Drop(1)));
        scope.get().submit(7, List.of(new SceneGeometrySink.Drop(2)));
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.updateScenes(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));

        assertEquals(List.of(1L, 2L), forwarded.stream().map(GeometryUpdates.Group::revision).toList());
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

        manager.submitGeometry(null, origin,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));
        manager.submitGeometry(null, origin,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));

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
                frame.geometry().submit(0, operations, () -> meshesPublished = true);
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(id("cloud"), cloud), Map.of(),
                Map.of(id("materials"), defining("cloud")));
        manager.collectMaterials();
        List<GeometryUpdates.Group> forwarded = new ArrayList<>();

        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));
        cell.incrementAndGet();
        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, failure) -> forwarded.addAll(updates));

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

        manager.submitGeometry(null, SceneOrigin.ZERO, (updates, acknowledgment, handler) -> {
            if (updates.getFirst().key().source().equals(id("broken"))) failure.set(handler);
        });
        assertTrue(failure.get() != null);
        failure.get().accept(new IllegalStateException("expected"));
        manager.submitGeometry(null, SceneOrigin.ZERO,
                (updates, acknowledgment, ignoredFailure) ->
                        assertEquals(id("healthy"), updates.getFirst().key().source()));

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

    @Test
    void failedMaterialSourceRetiresBorrowedTextureAndDoesNotConsumeItsSlot() {
        AtomicInteger retired = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        AtomicInteger healthySlot = new AtomicInteger();
        MaterialSource broken = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink materials) {
                materials.register(new dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture(301L,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, retired::incrementAndGet));
                throw new IllegalStateException("expected");
            }

            @Override
            public void stop() {
                stopped.incrementAndGet();
            }
        };
        MaterialSource healthy = materials -> healthySlot.set(materials.register(
                new dev.comfyfluffy.caustica.api.provider.CpuTextureResource(1, 1,
                        dev.comfyfluffy.caustica.api.provider.CpuTextureResource.Encoding.SRGB,
                        new byte[] { 1, 2, 3, 4 })));
        Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
        sources.put(id("broken"), broken);
        sources.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), sources);
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2,
                (texture, label) -> uploaded(100L, new AtomicInteger()), (slot, imageView, layout) -> { });
        manager.bindTextureRegistry(registry);

        manager.collectMaterials();

        assertEquals(1, retired.get());
        assertEquals(1, stopped.get());
        assertEquals(1, healthySlot.get());
        assertEquals(1, registry.size());
        manager.unbindTextureRegistry(registry);
        registry.close();
    }

    @Test
    void materialCommitHookDoesNotRunWhenTextureUploadFails() {
        AtomicInteger uploads = new AtomicInteger();
        AtomicReference<String> semantics = new AtomicReference<>("prior");
        AtomicInteger stops = new AtomicInteger();
        MaterialSource broken = new MaterialSource() {
            @Override
            public void submitMaterials(dev.comfyfluffy.caustica.api.provider.MaterialSink sink) {
                sink.register(new dev.comfyfluffy.caustica.api.provider.CpuTextureResource(1, 1,
                        dev.comfyfluffy.caustica.api.provider.CpuTextureResource.Encoding.SRGB,
                        new byte[]{1, 2, 3, 4}));
                sink.define(definition("uncommitted"));
                sink.onCommit(() -> semantics.set("next"));
            }

            @Override public void stop() { stops.incrementAndGet(); }
        };
        ProviderManager manager = new ProviderManager(Map.of(), Map.of(), Map.of(id("broken"), broken));
        ProviderTextureRegistry registry = new ProviderTextureRegistry(3, (texture, label) -> {
            if (uploads.getAndIncrement() > 0) throw new IllegalStateException("upload failed");
            return uploaded(100L, new AtomicInteger());
        }, (slot, imageView, layout) -> { });
        manager.bindTextureRegistry(registry);

        ProviderManager.MaterialContributions contributions = manager.collectMaterials();

        assertTrue(contributions.definitions().isEmpty());
        assertEquals("prior", semantics.get());
        assertEquals(1, stops.get());
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

    private static MaterialSource defining(String path) {
        return sink -> sink.define(definition(path));
    }

    private static MaterialDefinition definition(String path) {
        return new MaterialDefinition(new MaterialHandle(id(path)), 1.0f, 1.0f, 1.0f,
                1.0f, 0.0f, 1.5f, 0.0f, MaterialTopology.SURFACE, id("surface"));
    }

    private static SceneMesh triangle(String material) {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], List.of(
                SceneMesh.TriangleSurface.surface(MaterialHandle.of("test", material))));
    }

    private static GeometryUpdates.BuildPolicy buildPolicy(GeometryUpdates.Group group) {
        GeometryUpdates.Put put = (GeometryUpdates.Put) group.operations().getFirst();
        return ((GeometryUpdates.ProviderPayload) put.payload()).buildPolicy();
    }

    private static SceneMesh fallbackTriangle() {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], List.of(new SceneMesh.TriangleSurface(
                new SceneMesh.FallbackMaterial(null),
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
