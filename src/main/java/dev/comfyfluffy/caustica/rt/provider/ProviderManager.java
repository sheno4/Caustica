package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightSink;
import dev.comfyfluffy.caustica.engine.light.DistantLight;
import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
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
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.function.Supplier;

public final class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager(null, null, null);

    // A provider instance is runtime-activation-scoped. Failures disable it until that activation ends.
    private final Set<ProviderKey> failed = new HashSet<>();
    private final Set<ProviderKey> stoppedThisSession = new HashSet<>();
    private final Set<ProviderKey> shutDownThisSession = new HashSet<>();
    private Map<ResourceId, SceneProvider> scenes;
    private Map<ResourceId, LightProvider> lights;
    private Map<ResourceId, MaterialSource> materials;
    private List<LightDescriptor> frameLights = List.of();
    private Set<ResourceId> namedMaterials = Set.of();
    private RtSceneGeometryManager sceneGeometry;
    private final Map<GeometryGroupKey, Long> geometryRevisions = new HashMap<>();

    ProviderManager(Map<ResourceId, SceneProvider> scenes, Map<ResourceId, LightProvider> lights,
                    Map<ResourceId, MaterialSource> materials) {
        this.scenes = scenes;
        this.lights = lights;
        this.materials = materials;
    }

    /** Begin a new RT session; normally stopped providers become eligible for callbacks again. */
    public void beginSession() {
        failed.clear();
        stoppedThisSession.clear();
        shutDownThisSession.clear();
        geometryRevisions.clear();
    }

    /** Install the freshly created runtime-activation provider instances before they receive callbacks. */
    public void beginSession(CausticaRegistry.RuntimeContributions contributions) {
        scenes = contributions.sceneProviders();
        lights = contributions.lightProviders();
        materials = contributions.materialSources();
        beginSession();
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
                submitted.addAll(providerLights);
            } catch (Throwable t) {
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

    /** Select the single active primary scene source and snapshot its retained environment state. */
    public PrimaryScene primaryScene() {
        SceneSourceEntry entry = primarySceneSource();
        if (entry == null) {
            return null;
        }
        RtSceneSource.Retained retained = invokeSceneSource(entry, "retained scene",
                entry.source()::retainedScene, null);
        return retained != null ? new PrimaryScene(entry.id(), retained) : null;
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

    public int bindlessTextureCapacity() {
        SceneSourceEntry entry = primarySceneSource();
        return entry != null
                ? invokeSceneSource(entry, "bindless texture capacity", entry.source()::bindlessTextureCapacity, 1)
                : 1;
    }

    public void resetBindlessTextures(int capacity) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry != null) {
            invokeSceneSource(entry, "bindless texture reset", () -> {
                entry.source().resetBindlessTextures(capacity);
                return null;
            }, null);
        }
    }

    public void rebindTextures(RtPipeline pipeline, long sampler) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry != null) {
            invokeSceneSource(entry, "bindless texture rebind", () -> {
                entry.source().rebindTextures(pipeline, sampler);
                return null;
            }, null);
        }
    }

    public void uploadPendingTextures(RtPipeline pipeline, long sampler) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry != null) {
            invokeSceneSource(entry, "bindless texture upload", () -> {
                entry.source().uploadPendingTextures(pipeline, sampler);
                return null;
            }, null);
        }
    }

    /** Resolve a source-owned texture identity without exposing source texture tables to geometry producers. */
    public int bindlessTextureSlot(SceneMesh.TextureReference texture) {
        if (texture == null) return 0;
        SceneSourceEntry entry = primarySceneSource();
        return entry != null ? invokeSceneSource(entry, "bindless texture slot",
                () -> entry.source().bindlessTextureSlot(texture), 0) : 0;
    }

    /** Collect each scene source transactionally into source-qualified retained-geometry groups. */
    public void submitGeometry(GpuContext ctx, SceneOrigin origin) {
        submitGeometry(ctx, origin, SceneCamera.IDENTITY);
    }

    /** Collect each scene source transactionally into source-qualified retained-geometry groups. */
    public void submitGeometry(GpuContext ctx, SceneOrigin origin, SceneCamera camera) {
        submitGeometry(ctx, origin, (provider, sink) -> provider.submitGeometry(new SceneFrameContext(sink,
                origin.x(), origin.y(), origin.z(), camera)), (updates, acknowledgment, failureHandler) ->
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
                origin.x(), origin.y(), origin.z(), SceneCamera.IDENTITY)),
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
                collector.collect(entry.getValue(), stagingSink);
                List<RtSceneGeometryManager.GeometryUpdateGroup> updates = new ArrayList<>(stagedGroups.size());
                Map<SubmittedGeometryGroupKey, PublishedGeometryGroup> publishedGroups = new HashMap<>();
                for (StagedGeometryGroup group : stagedGroups.values()) {
                    RtSceneGeometryManager.GeometryUpdateGroup update = toUpdate(entry.getKey(), group.groupKey(),
                            group.operations(), origin);
                    updates.add(update);
                    publishedGroups.put(new SubmittedGeometryGroupKey(update.key(), update.revision()),
                            new PublishedGeometryGroup(group.groupKey(), group.onPublished()));
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

    private RtSceneGeometryManager.GeometryUpdateGroup toUpdate(ResourceId source, SceneGeometryKey groupKey,
                                                                 List<SceneGeometrySink.Operation> operations,
                                                                 SceneOrigin origin) {
        ArrayList<RtSceneGeometryManager.GeometryOperation> converted = new ArrayList<>(operations.size());
        for (SceneGeometrySink.Operation operation : operations) {
            switch (operation) {
                case SceneGeometrySink.Put put -> {
                    validateMeshMaterials(put.mesh());
                    converted.add(new RtSceneGeometryManager.Put(put.residentKey(),
                            new RtSceneGeometryManager.ProviderPayload(put.mesh(), put.buildOptions())));
                }
                case SceneGeometrySink.Drop drop ->
                    converted.add(new RtSceneGeometryManager.Drop(drop.residentKey()));
                case SceneGeometrySink.Place place -> converted.add(new RtSceneGeometryManager.Place(
                        place.instanceKey(), place.residentKey(),
                        place.transform().relativeTo(origin.x(), origin.y(), origin.z()), place.mask(), origin));
                case SceneGeometrySink.Remove remove ->
                    converted.add(new RtSceneGeometryManager.Remove(remove.instanceKey()));
            }
        }
        GeometryGroupKey revisionKey = new GeometryGroupKey(source, groupKey);
        long revision = geometryRevisions.merge(revisionKey, 1L, Math::addExact);
        return new RtSceneGeometryManager.GeometryUpdateGroup(new RtSceneGeometryManager.GroupKey(source, groupKey),
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
        List<MaterialRule> result = new ArrayList<>();
        for (Map.Entry<ResourceId, MaterialSource> entry : materials().entrySet()) {
            ProviderKey key = new ProviderKey("material", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            List<MaterialDefinition> stagedDefinitions = new ArrayList<>();
            List<MaterialRule> staged = new ArrayList<>();
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
        return new MaterialContributions(definitions, result);
    }

    public record MaterialContributions(List<MaterialDefinition> definitions, List<MaterialRule> rules) {
        public MaterialContributions {
            definitions = List.copyOf(definitions);
            rules = List.copyOf(rules);
        }
    }

    /** Stop every provider before session GPU work is drained. No GPU owner is released in this phase. */
    public void stopProviders() {
        frameLights = List.of();
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

    /** Drop the render-session instance references after every provider has shut down. */
    public void endSession() {
        scenes = Map.of();
        lights = Map.of();
        materials = Map.of();
        frameLights = List.of();
        namedMaterials = Set.of();
    }

    private void clearAllSceneGeometry() {
        GpuContext ctx = GpuContext.currentOrNull();
        for (ResourceId source : scenes().keySet()) clearSceneGeometry(ctx, source);
    }

    private Map<ResourceId, SceneProvider> scenes() {
        return scenes != null ? scenes : Map.of();
    }

    private Map<ResourceId, LightProvider> lights() {
        return lights != null ? lights : Map.of();
    }

    private Map<ResourceId, MaterialSource> materials() {
        return materials != null ? materials : Map.of();
    }

    private SceneSourceEntry primarySceneSource() {
        SceneSourceEntry selected = null;
        for (Map.Entry<ResourceId, SceneProvider> entry : scenes().entrySet()) {
            ProviderKey key = new ProviderKey("scene", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)
                    || !(entry.getValue() instanceof RtSceneSource source)) {
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException("multiple primary scene sources are active: "
                        + selected.id() + " and " + entry.getKey());
            }
            selected = new SceneSourceEntry(entry.getKey(), entry, key, source);
        }
        return selected;
    }

    private SceneSourceEntry requireSceneSource(ResourceId id) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry == null || !entry.id().equals(id)) {
            throw new SceneSourceUnavailableException(id);
        }
        return entry;
    }

    private <T> T invokeSceneSource(SceneSourceEntry entry, String operation, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Throwable failure) {
            failed.add(entry.key());
            CausticaMod.LOGGER.error("Caustica scene provider {} failed during {} and was disabled",
                    entry.id(), operation, failure);
            stopOne("scene", entry.provider(), entry.key(), SceneProvider::stop);
            if (failure instanceof Error error) {
                throw error;
            }
            return fallback;
        }
    }

    /** The selected primary scene source disappeared while the current frame was being assembled. */
    public static final class SceneSourceUnavailableException extends RuntimeException {
        SceneSourceUnavailableException(ResourceId provider) {
            super("primary scene source is unavailable: " + provider);
        }
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

    @FunctionalInterface
    interface GeometrySubmitter {
        void submit(List<RtSceneGeometryManager.GeometryUpdateGroup> updates, Consumer<Throwable> failureHandler);
    }

    @FunctionalInterface
    private interface GeometrySubmission {
        void submit(List<RtSceneGeometryManager.GeometryUpdateGroup> updates,
                    Consumer<RtSceneGeometryManager.PublicationAck> acknowledgment,
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

    private record SubmittedGeometryGroupKey(RtSceneGeometryManager.GroupKey key, long revision) {
    }

    private record PublishedGeometryGroup(SceneGeometryKey groupKey, Consumer<SceneGeometrySink.Publication> onPublished) {
    }

    private record SceneSourceEntry(ResourceId id, Map.Entry<ResourceId, SceneProvider> provider,
                                    ProviderKey key, RtSceneSource source) {
    }

    public record PrimaryScene(ResourceId provider, RtSceneSource.Retained retained) {
    }
}
