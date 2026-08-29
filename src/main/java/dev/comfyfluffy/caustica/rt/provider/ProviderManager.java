package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightSink;
import dev.comfyfluffy.caustica.api.provider.RetainedLightCollection;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;
import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.ProviderLifecycle;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext;
import dev.comfyfluffy.caustica.api.provider.SceneCamera;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.api.provider.TextureResource;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.geometry.GeometryUpdates;
import dev.comfyfluffy.caustica.rt.texture.ProviderTextureRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private RetainedLightSnapshot retainedLights = RetainedLightSnapshot.empty(0L);
    private long retainedLightGeneration;
    private Set<ResourceId> namedMaterials = Set.of();
    private RtSceneGeometryManager sceneGeometry;
    private final Map<GeometryGroupKey, Long> geometryRevisions = new HashMap<>();
    private final Map<ResourceId, QueuedSceneScope> sceneScopes = new HashMap<>();
    private final AtomicBoolean sceneResetRequested = new AtomicBoolean();
    private volatile long sceneGeneration;
    private ProviderTextureRegistry textureRegistry;

    ProviderManager(Map<ResourceId, SceneProvider> scenes, Map<ResourceId, LightProvider> lights,
                    Map<ResourceId, MaterialSource> materials) {
        this.scenes = scenes;
        this.lights = lights;
        this.materials = materials;
    }

    public ProviderManager(CausticaRegistry.RuntimeContributions contributions) {
        this(contributions.sceneProviders(), contributions.lightProviders(), contributions.materialSources());
        beginSession();
    }

    /** Begin a new runtime activation; normally stopped providers become eligible for callbacks again. */
    public void beginSession() {
        closeSceneScopes();
        failed.clear();
        stoppedThisSession.clear();
        shutDownThisSession.clear();
        geometryRevisions.clear();
        retainedLightGroups.clear();
        retainedLightProviderGenerations.clear();
        retainedLights = RetainedLightSnapshot.empty(++retainedLightGeneration);
        for (Map.Entry<ResourceId, SceneProvider> entry : scenes().entrySet()) {
            ProviderKey key = new ProviderKey("scene", entry.getKey());
            QueuedSceneScope scope = new QueuedSceneScope(() -> sceneResetRequested.set(true));
            sceneScopes.put(entry.getKey(), scope);
            try {
                entry.getValue().onSessionStart(scope);
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica scene provider {} failed during session start and was disabled",
                        entry.getKey(), t);
                stopOne("scene", entry, key, SceneProvider::stop);
            }
        }
    }

    public void prepareFrame() {
        invoke("scene", scenes(), SceneProvider::prepareFrame, SceneProvider::stop);
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
    public void submitGeometry(GpuContext ctx, SceneOrigin origin, long frameIndex, SceneCamera camera) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometry(new SceneFrameContext(sink,
                origin.x(), origin.y(), origin.z(), frameIndex, camera)), GeometryUpdates.BuildPolicy.DYNAMIC,
                false,
                (updates, acknowledgment, failureHandler) ->
                        sceneGeometry().submit(updates, acknowledgment, failureHandler));
    }

    /** Produce and collect retained geometry at the host update cadence. */
    public void updateScenes(GpuContext ctx, SceneOrigin origin) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.update(new SceneGeometryUpdateContext(sink,
                origin.x(), origin.y(), origin.z())), GeometryUpdates.BuildPolicy.STATIC,
                true,
                (updates, acknowledgment, failureHandler) ->
                        sceneGeometry().submit(updates, acknowledgment, failureHandler));
    }

    void updateScenes(GpuContext ctx, SceneOrigin origin, GeometrySubmitter submitter) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.update(new SceneGeometryUpdateContext(sink,
                origin.x(), origin.y(), origin.z())), GeometryUpdates.BuildPolicy.STATIC,
                true, submitter);
    }

    void submitGeometry(GpuContext ctx, SceneOrigin origin, GeometrySubmitter submitter) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometry(new SceneFrameContext(sink,
                origin.x(), origin.y(), origin.z(), 0L, SceneCamera.IDENTITY)),
                GeometryUpdates.BuildPolicy.DYNAMIC, false, submitter);
    }

    private void submitGeometry(GpuContext ctx, SceneOrigin origin, GeometryCollector collector,
                                GeometryUpdates.BuildPolicy buildPolicy,
                                boolean drainScopes,
                                GeometrySubmitter submitter) {
        long submissionGeneration = sceneGeneration;
        for (Map.Entry<ResourceId, SceneProvider> entry : scenes().entrySet()) {
            ProviderKey key = new ProviderKey("scene", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            Map<SceneGeometryKey, StagedGeometryGroup> stagedGroups = new java.util.LinkedHashMap<>();
            SceneGeometrySink stagingSink = new SceneGeometrySink() {
                @Override
                public void submit(SceneGeometryKey groupKey, List<SceneGeometrySink.Operation> operations,
                                   Runnable onPublished) {
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
                List<StagedGeometryGroup> collected = new ArrayList<>();
                if (drainScopes) {
                    QueuedSceneScope scope = sceneScopes.get(entry.getKey());
                    if (scope != null) collected.addAll(scope.drain());
                }
                collected.addAll(stagedGroups.values());
                List<GeometryUpdates.Group> updates = new ArrayList<>(collected.size());
                Map<SubmittedGeometryGroupKey, Runnable> publishedGroups = new HashMap<>();
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.providerConvert")) {
                    for (StagedGeometryGroup group : collected) {
                        GeometryUpdates.Group update = toUpdate(entry.getKey(), group.groupKey(),
                                group.operations(), origin, buildPolicy);
                        updates.add(update);
                        publishedGroups.put(new SubmittedGeometryGroupKey(update.key(), update.revision()),
                                group.onPublished());
                    }
                }
                if (updates.isEmpty()) {
                    continue;
                }
                submitter.submit(updates, acknowledgement -> {
                    if (submissionGeneration != sceneGeneration) return;
                    Runnable onPublished = publishedGroups.remove(new SubmittedGeometryGroupKey(
                            acknowledgement.key(), acknowledgement.revision()));
                    if (onPublished == null) {
                        return;
                    }
                    try {
                        onPublished.run();
                    } catch (Throwable t) {
                        failSceneGeometry(ctx, entry, key, t);
                    }
                }, failure -> {
                    if (submissionGeneration == sceneGeneration) failSceneGeometry(ctx, entry, key, failure);
                });
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
                                                                 SceneOrigin origin,
                                                                 GeometryUpdates.BuildPolicy buildPolicy) {
        ArrayList<GeometryUpdates.GeometryOperation> converted = new ArrayList<>(operations.size());
        for (SceneGeometrySink.Operation operation : operations) {
            switch (operation) {
                case SceneGeometrySink.Put put -> {
                    validateMeshMaterials(put.mesh());
                    converted.add(new GeometryUpdates.Put(put.residentKey(),
                            new GeometryUpdates.ProviderPayload(put.mesh(), buildPolicy)));
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

    /** Apply one coalesced provider-requested reset on the engine update/render thread. */
    public boolean consumeSceneResetRequest() {
        if (!sceneResetRequested.getAndSet(false)) return false;
        sceneGeneration = Math.incrementExact(sceneGeneration);
        invalidateSceneScopes();
        clearAllSceneGeometry();
        frameLights = List.of();
        retainedLightGroups.clear();
        retainedLightProviderGenerations.clear();
        retainedLights = RetainedLightSnapshot.empty(++retainedLightGeneration);
        return true;
    }

    public void onResourcePackClosing() {
        invalidateSceneScopes();
        clearAllSceneGeometry();
        clearMaterials();
        invokeLifecycle(ProviderLifecycle::onResourcePackClosing);
    }

    /**
     * Notify material sources first, then scene and light providers. This guarantees replacement material
     * resources are ready before scene callbacks and before the host's next material collection.
     */
    public void onResourcePackApplied() {
        invokeLifecycle(ProviderLifecycle::onResourcePackApplied);
    }

    private void invokeLifecycle(Consumer<ProviderLifecycle> callback) {
        invoke("material", materials(), callback::accept, MaterialSource::stop);
        invoke("scene", scenes(), callback::accept, SceneProvider::stop);
        invoke("light", lights(), callback::accept, LightProvider::stop);
    }

    /**
     * Collect one immutable material-definition snapshot. Submission failures cannot publish partial source
     * contributions. Commit hooks run after publication and must not throw because their failure aborts the epoch.
     */
    public MaterialContributions collectMaterials() {
        List<MaterialDefinition> definitions = new ArrayList<>();
        Set<ResourceId> definedMaterials = new HashSet<>();
        for (Map.Entry<ResourceId, MaterialSource> entry : materials().entrySet()) {
            ProviderKey key = new ProviderKey("material", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            List<MaterialDefinition> stagedDefinitions = new ArrayList<>();
            List<Runnable> stagedCommitActions = new ArrayList<>();
            ProviderTextureRegistry.Submission textureSubmission = textureRegistry == null
                    ? null : textureRegistry.submission(entry.getKey());
            boolean committed = false;
            try (textureSubmission) {
                entry.getValue().submitMaterials(new MaterialSink() {
                    @Override
                    public int register(TextureResource resource) {
                        if (textureSubmission == null) {
                            throw new IllegalStateException("provider texture registry is not bound");
                        }
                        return textureSubmission.register(resource);
                    }

                    @Override
                    public void define(MaterialDefinition definition) {
                        stagedDefinitions.add(java.util.Objects.requireNonNull(definition));
                    }

                    @Override
                    public void onCommit(Runnable action) {
                        stagedCommitActions.add(java.util.Objects.requireNonNull(action));
                    }
                });
                Set<ResourceId> stagedIds = new HashSet<>();
                for (MaterialDefinition definition : stagedDefinitions) {
                    if (definedMaterials.contains(definition.id()) || !stagedIds.add(definition.id())) {
                        throw new IllegalStateException("duplicate material definition " + definition.id());
                    }
                }
                if (textureSubmission != null) textureSubmission.commit();
                definedMaterials.addAll(stagedIds);
                definitions.addAll(stagedDefinitions);
                committed = true;
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica material provider {} failed and was disabled", entry.getKey(), t);
                stopOne("material", entry, key, MaterialSource::stop);
            }
            if (committed) stagedCommitActions.forEach(Runnable::run);
        }
        namedMaterials = Set.copyOf(definedMaterials);
        return new MaterialContributions(definitions);
    }

    public record MaterialContributions(List<MaterialDefinition> definitions) {
        public MaterialContributions {
            definitions = List.copyOf(definitions);
        }
    }

    /** Stop every provider before session GPU work is drained. No GPU owner is released in this phase. */
    public void stopProviders() {
        closeSceneScopes();
        frameLights = List.of();
        retainedLightGroups.clear();
        retainedLightProviderGenerations.clear();
        retainedLights = RetainedLightSnapshot.empty(++retainedLightGeneration);
        namedMaterials = Set.of();
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
        closeSceneScopes();
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

    private void invalidateSceneScopes() {
        sceneScopes.values().forEach(QueuedSceneScope::invalidate);
    }

    private void closeSceneScopes() {
        sceneScopes.values().forEach(QueuedSceneScope::close);
        sceneScopes.clear();
        sceneResetRequested.set(false);
        sceneGeneration = Math.incrementExact(sceneGeneration);
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
            QueuedSceneScope scope = sceneScopes.remove(entry.getKey());
            if (scope != null) scope.close();
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
        void submit(List<GeometryUpdates.Group> updates,
                    Consumer<GeometryUpdates.Publication> acknowledgment,
                    Consumer<Throwable> failureHandler);
    }

    @FunctionalInterface
    private interface GeometryCollector {
        void collect(SceneProvider provider, SceneGeometrySink sink);
    }

    private record StagedGeometryGroup(SceneGeometryKey groupKey, List<SceneGeometrySink.Operation> operations,
                                       Runnable onPublished) {
        private StagedGeometryGroup {
            operations = List.copyOf(operations);
            onPublished = java.util.Objects.requireNonNull(onPublished, "onPublished");
        }
    }

    private record SubmittedGeometryGroupKey(GeometryUpdates.GroupKey key, long revision) {
    }

    private static final class QueuedSceneScope implements SceneScope {
        private final ArrayDeque<QueuedGeometryGroup> queued = new ArrayDeque<>();
        private final Runnable requestReset;
        private boolean open = true;
        private long generation;

        private QueuedSceneScope(Runnable requestReset) {
            this.requestReset = java.util.Objects.requireNonNull(requestReset, "requestReset");
        }

        @Override
        public synchronized void requestSceneReset() {
            if (open) requestReset.run();
        }

        @Override
        public synchronized void submit(SceneGeometryKey groupKey, List<SceneGeometrySink.Operation> operations,
                                        Runnable onPublished) {
            if (!open) throw new IllegalStateException("scene scope is closed");
            queued.addLast(new QueuedGeometryGroup(generation, groupKey, operations, onPublished));
        }

        synchronized List<StagedGeometryGroup> drain() {
            ArrayList<StagedGeometryGroup> drained = new ArrayList<>(queued.size());
            while (!queued.isEmpty()) {
                QueuedGeometryGroup group = queued.removeFirst();
                if (group.generation() != generation) continue;
                drained.add(new StagedGeometryGroup(group.groupKey(), group.operations(), () -> {
                    synchronized (QueuedSceneScope.this) {
                        if (!open || group.generation() != generation) return;
                    }
                    group.onPublished().run();
                }));
            }
            return drained;
        }

        synchronized void invalidate() {
            generation = Math.incrementExact(generation);
            queued.clear();
        }

        synchronized void close() {
            open = false;
            generation = Math.incrementExact(generation);
            queued.clear();
        }
    }

    private record QueuedGeometryGroup(long generation, SceneGeometryKey groupKey,
                                       List<SceneGeometrySink.Operation> operations, Runnable onPublished) {
        private QueuedGeometryGroup {
            java.util.Objects.requireNonNull(groupKey, "groupKey");
            operations = List.copyOf(operations);
            onPublished = java.util.Objects.requireNonNull(onPublished, "onPublished");
        }
    }

}
