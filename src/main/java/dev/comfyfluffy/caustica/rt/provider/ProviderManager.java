package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightSink;
import dev.comfyfluffy.caustica.api.provider.RetainedLightCollection;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;
import dev.comfyfluffy.caustica.engine.light.DistantLight;
import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext;
import dev.comfyfluffy.caustica.api.provider.SceneCamera;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.geometry.GeometryUpdates;
import dev.comfyfluffy.caustica.rt.texture.ProviderTextureRegistry;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

public final class ProviderManager {
    // A provider instance is runtime-activation-scoped. Failures disable it until that activation ends.
    private final Set<ProviderKey> failed = new HashSet<>();
    private final Set<ProviderKey> stoppedThisSession = new HashSet<>();
    private final Set<ProviderKey> shutDownThisSession = new HashSet<>();
    private final Map<ResourceId, SceneProvider> scenes;
    private final Map<ResourceId, LightProvider> lights;
    private final Map<ResourceId, MaterialSource> materials;
    private List<LightDescriptor> frameLights = List.of();
    private final Map<RetainedLightGroupKey, RetainedLightBatch> retainedLightGroups = new HashMap<>();
    private final Map<ResourceId, Long> retainedLightProviderGenerations = new HashMap<>();
    private final Consumer<LightDescriptor.Finite> retainedLightValidator;
    private RetainedLightSnapshot retainedLights = RetainedLightSnapshot.empty(0L);
    private long retainedLightGeneration;
    private Set<ResourceId> namedMaterials = Set.of();
    private RtSceneGeometryManager sceneGeometry;
    private Runnable stopRetainedLightWork = () -> { };
    private final Map<GeometryGroupKey, Long> geometryRevisions = new HashMap<>();
    private ProviderTextureRegistry textureRegistry;

    ProviderManager(Map<ResourceId, SceneProvider> scenes, Map<ResourceId, LightProvider> lights,
                    Map<ResourceId, MaterialSource> materials) {
        this(scenes, lights, materials, light -> FiniteLight.from(light, 1.0));
    }

    ProviderManager(Map<ResourceId, SceneProvider> scenes, Map<ResourceId, LightProvider> lights,
                    Map<ResourceId, MaterialSource> materials,
                    Consumer<LightDescriptor.Finite> retainedLightValidator) {
        this.scenes = scenes;
        this.lights = lights;
        this.materials = materials;
        this.retainedLightValidator = java.util.Objects.requireNonNull(retainedLightValidator,
                "retainedLightValidator");
    }

    public ProviderManager(CausticaRegistry.RuntimeContributions contributions) {
        this(contributions.sceneProviders(), contributions.lightProviders(), contributions.materialSources());
        beginSession();
    }

    /** Begin a new RT session; normally stopped providers become eligible for callbacks again. */
    public void beginSession() {
        failed.clear();
        stoppedThisSession.clear();
        shutDownThisSession.clear();
        geometryRevisions.clear();
        retainedLightGroups.clear();
        retainedLightProviderGenerations.clear();
        retainedLights = RetainedLightSnapshot.empty(++retainedLightGeneration);
    }

    public void updateScenes() {
        invoke("scene", scenes(), SceneProvider::update, SceneProvider::stop);
    }

    public void prepareFrame() {
        invoke("scene", scenes(), SceneProvider::prepareFrame, SceneProvider::stop);
        invoke("light", lights(), LightProvider::prepareFrame, LightProvider::stop);
        ArrayList<LightDescriptor> submitted = new ArrayList<>();
        for (Map.Entry<ResourceId, LightProvider> entry : lights().entrySet()) {
            ProviderKey key = new ProviderKey("light", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            ArrayList<LightDescriptor> providerLights = new ArrayList<>();
            Set<Long> providerKeys = new HashSet<>();
            LightSink sink = light -> {
                if (!providerKeys.add(light.key())) {
                    throw new IllegalArgumentException("duplicate light key " + light.key());
                }
                switch (light) {
                    case LightDescriptor.Finite finite -> FiniteLight.from(finite, 1.0);
                    case LightDescriptor.Distant distant -> DistantLight.from(distant);
                }
                providerLights.add(light);
            };
            try {
                entry.getValue().submitLights(sink);
                updateRetainedLights(entry.getKey(), entry.getValue().retainedLights());
                submitted.addAll(providerLights);
            } catch (Throwable t) {
                removeRetainedLights(entry.getKey());
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica light provider {} failed and was disabled",
                        entry.getKey(), t);
                stopOne("light", entry, key, LightProvider::stop);
            }
        }
        frameLights = List.copyOf(submitted);
    }

    /** Immutable light snapshot assembled by {@link #prepareFrame()}. */
    public List<LightDescriptor> frameLights() {
        return frameLights;
    }

    /** Immutable source-qualified retained-light snapshot assembled by {@link #prepareFrame()}. */
    public RetainedLightSnapshot retainedLights() {
        return retainedLights;
    }

    private void updateRetainedLights(ResourceId source, RetainedLightCollection collection) {
        java.util.Objects.requireNonNull(collection, "retainedLights");
        Long previousGeneration = retainedLightProviderGenerations.get(source);
        if (previousGeneration != null && previousGeneration == collection.generation()) return;

        Map<Long, RetainedLightCollection.Group> submitted = new java.util.LinkedHashMap<>();
        for (RetainedLightCollection.Group group : collection.groups()) {
            if (submitted.putIfAbsent(group.key(), group) != null) {
                throw new IllegalArgumentException("duplicate retained-light key " + group.key());
            }
            group.lights().forEach(retainedLightValidator);
        }

        boolean changed = retainedLightGroups.keySet().removeIf(key ->
                key.source().equals(source) && !submitted.containsKey(key.key()));
        for (RetainedLightCollection.Group snapshot : submitted.values()) {
            RetainedLightGroupKey key = new RetainedLightGroupKey(source, snapshot.key());
            RetainedLightBatch previous = retainedLightGroups.get(key);
            if (previous != null && previous.revision() == snapshot.revision()) {
                continue;
            }
            retainedLightGroups.put(key, new RetainedLightBatch(source, snapshot.key(),
                    snapshot.revision(), snapshot.lights()));
            changed = true;
        }
        retainedLightProviderGenerations.put(source, collection.generation());
        if (changed) rebuildRetainedLightSnapshot();
    }

    private void removeRetainedLights(ResourceId source) {
        retainedLightProviderGenerations.remove(source);
        if (retainedLightGroups.keySet().removeIf(key -> key.source().equals(source))) {
            rebuildRetainedLightSnapshot();
        }
    }

    private void rebuildRetainedLightSnapshot() {
        ArrayList<RetainedLightBatch> batches = new ArrayList<>(retainedLightGroups.values());
        batches.sort(java.util.Comparator
                .comparing((RetainedLightBatch batch) -> batch.source().namespace())
                .thenComparing(batch -> batch.source().path())
                .thenComparingLong(RetainedLightBatch::key));
        retainedLights = new RetainedLightSnapshot(batches, ++retainedLightGeneration);
    }

    /** Bind the renderer-owned geometry manager for source lifecycle callbacks. */
    public void bindSceneGeometry(RtSceneGeometryManager geometry) {
        sceneGeometry = geometry;
    }

    /** Bind renderer-owned CPU work that must stop before the shared GPU executor is drained. */
    public void bindRetainedLightStop(Runnable stop) {
        stopRetainedLightWork = stop;
    }

    /** Renderer-owned geometry manager injected into host scene providers. */
    public RtSceneGeometryManager sceneGeometry() {
        if (sceneGeometry == null) throw new IllegalStateException("scene geometry is not bound");
        return sceneGeometry;
    }

    /** Bind the private texture table for the active material epoch and collect initial contributions. */
    public void bindTextureRegistry(ProviderTextureRegistry registry) {
        if (textureRegistry != null) throw new IllegalStateException("provider texture registry is already bound");
        textureRegistry = java.util.Objects.requireNonNull(registry, "registry");
        for (Map.Entry<ResourceId, SceneProvider> entry : scenes().entrySet()) {
            try {
                submitTextures(entry);
            } catch (Throwable failure) {
                failSceneTextures(entry, failure);
            }
        }
    }

    public void unbindTextureRegistry(ProviderTextureRegistry registry) {
        if (textureRegistry != registry) throw new IllegalStateException("provider texture registry does not match");
        textureRegistry = null;
    }

    public void publishMaterials(MaterialSnapshot snapshot) {
        invoke("scene", scenes(), provider -> provider.onMaterialEpoch(snapshot), SceneProvider::stop);
    }

    public void clearMaterials() {
        invoke("scene", scenes(), SceneProvider::onMaterialEpochClosing, SceneProvider::stop);
    }

    /** Collect each scene source transactionally into source-qualified retained-geometry groups. */
    public void submitGeometry(GpuContext ctx, SceneOrigin origin) {
        submitGeometry(ctx, origin, 0L, SceneCamera.IDENTITY);
    }

    /** Collect each scene source transactionally into source-qualified retained-geometry groups. */
    public void submitGeometry(GpuContext ctx, SceneOrigin origin, long frameIndex, SceneCamera camera) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometry(new SceneFrameContext(sink,
                origin.x(), origin.y(), origin.z(), frameIndex, camera)), (updates, acknowledgment, failureHandler) ->
                sceneGeometry().submit(updates, acknowledgment, failureHandler));
    }

    /** Collect update-cadence retained geometry before the first render frame can become active. */
    public void submitGeometryUpdates(GpuContext ctx, SceneOrigin origin) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometryUpdates(new SceneGeometryUpdateContext(sink,
                origin.x(), origin.y(), origin.z())), (updates, acknowledgment, failureHandler) ->
                sceneGeometry().submit(updates, acknowledgment, failureHandler));
    }

    void submitGeometryUpdates(GpuContext ctx, SceneOrigin origin, GeometrySubmitter submitter) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometryUpdates(new SceneGeometryUpdateContext(sink,
                origin.x(), origin.y(), origin.z())), (updates, acknowledgment, failureHandler) ->
                submitter.submit(updates, failureHandler));
    }

    void submitGeometry(GpuContext ctx, SceneOrigin origin, GeometrySubmitter submitter) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometry(new SceneFrameContext(sink,
                origin.x(), origin.y(), origin.z(), 0L, SceneCamera.IDENTITY)),
                (updates, acknowledgment, failureHandler) -> submitter.submit(updates, failureHandler));
    }

    private void submitGeometry(GpuContext ctx, SceneOrigin origin, GeometryCollector collector,
                                GeometrySubmission submitter) {
        for (Map.Entry<ResourceId, SceneProvider> entry : scenes().entrySet()) {
            ProviderKey key = new ProviderKey("scene", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            Map<SceneGeometryKey, StagedGeometryGroup> stagedGroups = new java.util.LinkedHashMap<>();
            SceneGeometrySink stagingSink = new SceneGeometrySink() {
                @Override
                public void submit(SceneGeometryKey groupKey, List<SceneGeometrySink.Operation> operations,
                                   Consumer<SceneGeometrySink.Publication> onPublished) {
                    StagedGeometryGroup group = new StagedGeometryGroup(groupKey, operations, onPublished);
                    if (stagedGroups.putIfAbsent(groupKey, group) != null) {
                        throw new IllegalArgumentException("duplicate geometry group key " + groupKey);
                    }
                }
            };
            try {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.providerCollect")) {
                    collector.collect(entry.getValue(), stagingSink);
                }
                submitTextures(entry);
                List<GeometryUpdates.Group> updates = new ArrayList<>(stagedGroups.size());
                Map<SubmittedGeometryGroupKey, PublishedGeometryGroup> publishedGroups = new HashMap<>();
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.providerConvert")) {
                    for (StagedGeometryGroup group : stagedGroups.values()) {
                        GeometryUpdates.Group update = toUpdate(entry.getKey(), group.groupKey(),
                                group.operations(), origin);
                        updates.add(update);
                        publishedGroups.put(new SubmittedGeometryGroupKey(update.key(), update.revision()),
                                new PublishedGeometryGroup(group.groupKey(), group.onPublished()));
                    }
                }
                submitter.submit(updates, acknowledgement -> {
                    PublishedGeometryGroup group = publishedGroups.remove(new SubmittedGeometryGroupKey(
                            acknowledgement.key(), acknowledgement.revision()));
                    if (group == null) {
                        return;
                    }
                    try {
                        group.onPublished().accept(new SceneGeometrySink.Publication(group.groupKey()));
                    } catch (Throwable t) {
                        failSceneGeometry(ctx, entry, key, t);
                    }
                }, failure -> failSceneGeometry(ctx, entry, key, failure));
            } catch (Throwable t) {
                failSceneGeometry(ctx, entry, key, t);
            }
        }
    }

    private void failSceneGeometry(GpuContext ctx, Map.Entry<ResourceId, SceneProvider> entry,
                                   ProviderKey key, Throwable failure) {
        if (!failed.add(key)) {
            return;
        }
        CausticaMod.LOGGER.error("Caustica scene provider {} failed and was disabled", entry.getKey(), failure);
        stopOne("scene", entry, key, SceneProvider::stop);
    }

    private GeometryUpdates.Group toUpdate(ResourceId source, SceneGeometryKey groupKey,
                                                                 List<SceneGeometrySink.Operation> operations,
                                                                 SceneOrigin origin) {
        ArrayList<GeometryUpdates.GeometryOperation> converted = new ArrayList<>(operations.size());
        for (SceneGeometrySink.Operation operation : operations) {
            switch (operation) {
                case SceneGeometrySink.Put put -> {
                    validateMeshMaterials(put.mesh());
                    converted.add(new GeometryUpdates.Put(put.residentKey(),
                            new GeometryUpdates.ProviderPayload(put.mesh(), put.buildOptions())));
                }
                case SceneGeometrySink.Drop drop ->
                    converted.add(new GeometryUpdates.Drop(drop.residentKey()));
                case SceneGeometrySink.Place place -> converted.add(new GeometryUpdates.Place(
                        place.instanceKey(), place.residentKey(),
                        place.transform().relativeTo(origin.x(), origin.y(), origin.z()), place.mask(), origin));
                case SceneGeometrySink.Transform transform -> converted.add(new GeometryUpdates.UpdatePlacement(
                        transform.instanceKey(), transform.transform().relativeTo(
                                origin.x(), origin.y(), origin.z()), transform.mask(), origin));
                case SceneGeometrySink.Remove remove ->
                    converted.add(new GeometryUpdates.Remove(remove.instanceKey()));
            }
        }
        GeometryGroupKey revisionKey = new GeometryGroupKey(source, groupKey);
        long revision = geometryRevisions.merge(revisionKey, 1L, Math::addExact);
        return new GeometryUpdates.Group(new GeometryUpdates.GroupKey(source, groupKey),
                revision, converted);
    }

    private void validateMeshMaterials(SceneMesh mesh) {
        for (SceneMesh.TriangleSurface surface : mesh.surfaces()) {
            if (surface.material() instanceof SceneMesh.NamedMaterial named
                    && !namedMaterials.contains(named.material().id())) {
                throw new IllegalArgumentException("geometry references unsubmitted material " + named.material().id());
            }
        }
    }

    private void clearSceneGeometry(GpuContext ctx, ResourceId source) {
        geometryRevisions.keySet().removeIf(key -> key.source.equals(source));
        if (sceneGeometry != null && ctx != null) {
            sceneGeometry.clearSource(ctx, source);
        }
    }

    public void onWorldChanged() {
        clearAllSceneGeometry();
        invoke("scene", scenes(), SceneProvider::onWorldChanged, SceneProvider::stop);
    }

    public void onResourcePackClosing() {
        clearAllSceneGeometry();
        clearMaterials();
        invoke("scene", scenes(), SceneProvider::onResourcePackClosing, SceneProvider::stop);
        invoke("light", lights(), LightProvider::onResourcePackClosing, LightProvider::stop);
        invoke("material", materials(), MaterialSource::onResourcePackClosing, MaterialSource::stop);
    }

    public void onResourcePackApplied() {
        invoke("scene", scenes(), SceneProvider::onResourcePackApplied, SceneProvider::stop);
        invoke("light", lights(), LightProvider::onResourcePackApplied, LightProvider::stop);
        invoke("material", materials(), MaterialSource::onResourcePackApplied, MaterialSource::stop);
    }

    /**
     * Collect one immutable material rule snapshot. A source stages into its own list so a failure cannot
     * publish a partial contribution; other sources remain active and retain their registration order.
     */
    public MaterialContributions collectMaterials() {
        return collectMaterials(CausticaApi.registry()::surfaceIndex);
    }

    /**
     * Collect material sources transactionally and validate every shader implementation name before its
     * contribution becomes visible. Geometry linkage later uses the exact successfully published set.
     */
    public MaterialContributions collectMaterials(ToIntFunction<ResourceId> surfaceIndex) {
        List<MaterialDefinition> definitions = new ArrayList<>();
        Set<ResourceId> definedMaterials = new HashSet<>();
        List<MaterialTextureAsset> atlasAssets = new ArrayList<>();
        List<MaterialTextureAsset> standaloneAssets = new ArrayList<>();
        Set<ResourceId> assetMaterials = new HashSet<>();
        List<MaterialRule> result = new ArrayList<>();
        for (Map.Entry<ResourceId, MaterialSource> entry : materials().entrySet()) {
            ProviderKey key = new ProviderKey("material", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            List<MaterialDefinition> stagedDefinitions = new ArrayList<>();
            List<MaterialRule> staged = new ArrayList<>();
            List<MaterialTextureAsset> stagedAssets = new ArrayList<>();
            try {
                entry.getValue().submitMaterials(new dev.comfyfluffy.caustica.api.provider.MaterialSink() {
                    @Override
                    public void define(MaterialDefinition definition) {
                        stagedDefinitions.add(java.util.Objects.requireNonNull(definition));
                    }

                    @Override
                    public void submit(MaterialRule rule) {
                        staged.add(java.util.Objects.requireNonNull(rule));
                    }

                    @Override
                    public void submitAsset(MaterialTextureAsset asset) {
                        stagedAssets.add(java.util.Objects.requireNonNull(asset));
                    }
                });
                Set<ResourceId> stagedIds = new HashSet<>();
                for (MaterialDefinition definition : stagedDefinitions) {
                    if (definition.surface() != null && surfaceIndex.applyAsInt(definition.surface()) < 0) {
                        throw new IllegalStateException("material definition " + definition.id()
                                + " references unregistered surface " + definition.surface());
                    }
                    if (definedMaterials.contains(definition.id()) || !stagedIds.add(definition.id())) {
                        throw new IllegalStateException("duplicate material definition " + definition.id());
                    }
                }
                for (MaterialRule rule : staged) {
                    ResourceId surface = rule.parameters().surface();
                    if (surface != null && surfaceIndex.applyAsInt(surface) < 0) {
                        throw new IllegalStateException("material rule " + rule.id()
                                + " references unregistered surface " + surface);
                    }
                }
                HashSet<ResourceId> stagedAssetIds = new HashSet<>();
                for (MaterialTextureAsset asset : stagedAssets) {
                    if (assetMaterials.contains(asset.material()) || !stagedAssetIds.add(asset.material())) {
                        throw new IllegalStateException("duplicate material texture asset " + asset.material());
                    }
                }
                assetMaterials.addAll(stagedAssetIds);
                for (MaterialTextureAsset asset : stagedAssets) {
                    switch (asset.kind()) {
                        case SHARED_ATLAS -> atlasAssets.add(asset);
                        case STANDALONE -> standaloneAssets.add(asset);
                    }
                }
                definedMaterials.addAll(stagedIds);
                definitions.addAll(stagedDefinitions);
                result.addAll(staged);
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica material provider {} failed and was disabled", entry.getKey(), t);
                stopOne("material", entry, key, MaterialSource::stop);
            }
        }
        namedMaterials = Set.copyOf(definedMaterials);
        MaterialCatalog catalog = new MaterialCatalog(atlasAssets, standaloneAssets);
        return new MaterialContributions(definitions, result, catalog);
    }

    public record MaterialContributions(List<MaterialDefinition> definitions, List<MaterialRule> rules,
                                        MaterialCatalog catalog) {
        public MaterialContributions {
            definitions = List.copyOf(definitions);
            rules = List.copyOf(rules);
            java.util.Objects.requireNonNull(catalog, "catalog");
        }
    }

    /** Stop every provider before session GPU work is drained. No GPU owner is released in this phase. */
    public void stopProviders() {
        frameLights = List.of();
        retainedLightGroups.clear();
        retainedLightProviderGenerations.clear();
        retainedLights = RetainedLightSnapshot.empty(++retainedLightGeneration);
        namedMaterials = Set.of();
        stopRetainedLightWork.run();
        stopRemaining("scene", scenes(), SceneProvider::stop);
        stopRemaining("light", lights(), LightProvider::stop);
        stopRemaining("material", materials(), MaterialSource::stop);
    }

    /** Release every stopped provider after session GPU work is idle. */
    public void shutdownResources() {
        shutdownStopped("scene", scenes(), SceneProvider::shutdown);
        shutdownStopped("light", lights(), LightProvider::shutdown);
        shutdownStopped("material", materials(), MaterialSource::shutdown);
    }

    /** Clear session-derived snapshots after every provider has shut down. */
    public void endSession() {
        frameLights = List.of();
        retainedLightGroups.clear();
        retainedLightProviderGenerations.clear();
        retainedLights = RetainedLightSnapshot.empty(++retainedLightGeneration);
        namedMaterials = Set.of();
        geometryRevisions.clear();
    }

    private void clearAllSceneGeometry() {
        GpuContext ctx = GpuContext.currentOrNull();
        for (ResourceId source : scenes().keySet()) clearSceneGeometry(ctx, source);
    }

    private Map<ResourceId, SceneProvider> scenes() {
        return scenes;
    }

    private Map<ResourceId, LightProvider> lights() {
        return lights;
    }

    private Map<ResourceId, MaterialSource> materials() {
        return materials;
    }

    private void submitTextures(Map.Entry<ResourceId, SceneProvider> entry) {
        if (textureRegistry == null) return;
        ProviderKey key = new ProviderKey("scene", entry.getKey());
        if (failed.contains(key) || stoppedThisSession.contains(key)) return;
        try (ProviderTextureRegistry.Submission submission = textureRegistry.submission(entry.getKey())) {
            entry.getValue().submitTextures(submission);
            submission.commit();
        }
    }

    private void failSceneTextures(Map.Entry<ResourceId, SceneProvider> entry, Throwable failure) {
        ProviderKey key = new ProviderKey("scene", entry.getKey());
        if (!failed.add(key)) return;
        CausticaMod.LOGGER.error("Caustica scene provider {} failed while submitting textures and was disabled",
                entry.getKey(), failure);
        stopOne("scene", entry, key, SceneProvider::stop);
    }

    private <T> void invoke(String kind, Map<ResourceId, T> providers, Consumer<T> action, Consumer<T> stop) {
        for (Map.Entry<ResourceId, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            try {
                action.accept(entry.getValue());
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica {} provider {} failed and was disabled", kind, entry.getKey(), t);
                stopOne(kind, entry, key, stop);
            }
        }
    }

    private <T> void stopRemaining(String kind, Map<ResourceId, T> providers, Consumer<T> action) {
        for (Map.Entry<ResourceId, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (failed.contains(key) && !stoppedThisSession.contains(key)) {
                continue;
            }
            stopOne(kind, entry, key, action);
        }
    }

    private <T> void stopOne(String kind, Map.Entry<ResourceId, T> entry, ProviderKey key, Consumer<T> action) {
        if (!stoppedThisSession.add(key)) {
            return;
        }
        if (kind.equals("scene")) {
            clearSceneGeometry(GpuContext.currentOrNull(), entry.getKey());
        } else if (kind.equals("light")) {
            removeRetainedLights(entry.getKey());
        }
        try {
            action.accept(entry.getValue());
        } catch (Throwable t) {
            failed.add(key);
            CausticaMod.LOGGER.error("Caustica {} provider {} failed while stopping", kind, entry.getKey(), t);
        }
    }

    private <T> void shutdownStopped(String kind, Map<ResourceId, T> providers, Consumer<T> action) {
        for (Map.Entry<ResourceId, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (!stoppedThisSession.contains(key) || !shutDownThisSession.add(key)) {
                continue;
            }
            try {
                action.accept(entry.getValue());
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica {} provider {} failed during shutdown", kind, entry.getKey(), t);
            }
        }
    }

    private record ProviderKey(String kind, ResourceId id) {
    }

    private record GeometryGroupKey(ResourceId source, SceneGeometryKey key) {
    }

    private record RetainedLightGroupKey(ResourceId source, long key) {
    }

    @FunctionalInterface
    interface GeometrySubmitter {
        void submit(List<GeometryUpdates.Group> updates, Consumer<Throwable> failureHandler);
    }

    @FunctionalInterface
    private interface GeometrySubmission {
        void submit(List<GeometryUpdates.Group> updates,
                    Consumer<GeometryUpdates.Publication> acknowledgment,
                    Consumer<Throwable> failureHandler);
    }

    @FunctionalInterface
    private interface GeometryCollector {
        void collect(SceneProvider provider, SceneGeometrySink sink);
    }

    private record StagedGeometryGroup(SceneGeometryKey groupKey, List<SceneGeometrySink.Operation> operations,
                                       Consumer<SceneGeometrySink.Publication> onPublished) {
        private StagedGeometryGroup {
            operations = List.copyOf(operations);
            onPublished = java.util.Objects.requireNonNull(onPublished, "onPublished");
        }
    }

    private record SubmittedGeometryGroupKey(GeometryUpdates.GroupKey key, long revision) {
    }

    private record PublishedGeometryGroup(SceneGeometryKey groupKey, Consumer<SceneGeometrySink.Publication> onPublished) {
    }

}
