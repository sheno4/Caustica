package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
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
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager(null, null, null);

    // Provider failures disable it for the process. Normal session shutdown is tracked separately:
    // providers are lazy/restartable and receive callbacks again after beginSession().
    private final Set<ProviderKey> failed = new HashSet<>();
    private final Set<ProviderKey> stoppedThisSession = new HashSet<>();
    private final Set<ProviderKey> shutDownThisSession = new HashSet<>();
    private final Map<ResourceId, SceneProvider> scenes;
    private final Map<ResourceId, LightProvider> lights;
    private final Map<ResourceId, MaterialSource> materials;
    private List<LightDescriptor> frameLights = List.of();

    ProviderManager(Map<ResourceId, SceneProvider> scenes, Map<ResourceId, LightProvider> lights,
                    Map<ResourceId, MaterialSource> materials) {
        this.scenes = scenes;
        this.lights = lights;
        this.materials = materials;
    }

    /** Begin a new RT session; normally stopped providers become eligible for callbacks again. */
    public void beginSession() {
        stoppedThisSession.clear();
        shutDownThisSession.clear();
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

    /** Select the single active optimized scene source and snapshot its retained frame state. */
    public PrimaryScene primaryScene() {
        SceneSourceEntry entry = primarySceneSource();
        if (entry == null) {
            return null;
        }
        RtSceneSource.Retained retained = invokeSceneSource(entry, "retained scene",
                entry.source()::retainedScene);
        return retained != null ? new PrimaryScene(entry.id(), retained) : null;
    }

    /** Append the selected source's frame-varying geometry to the renderer-assembled base table. */
    public RtSceneSource.Frame beginPrimaryFrame(PrimaryScene selected, GpuContext ctx,
                                                  List<RtAccel.Instance> baseInstances,
                                                  RtGeometryAbi.TablePrefix geometryTable,
                                                  RtSceneSource.Camera camera) {
        SceneSourceEntry entry = requireSceneSource(selected.provider());
        return invokeSceneSource(entry, "frame geometry",
                () -> entry.source().beginFrame(ctx, selected.retained(), baseInstances, geometryTable, camera));
    }

    public int bindlessTextureCapacity() {
        SceneSourceEntry entry = primarySceneSource();
        return entry != null
                ? invokeSceneSource(entry, "bindless texture capacity", entry.source()::bindlessTextureCapacity)
                : 1;
    }

    public void resetBindlessTextures(int capacity) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry != null) {
            invokeSceneSource(entry, "bindless texture reset", () -> {
                entry.source().resetBindlessTextures(capacity);
                return null;
            });
        }
    }

    public void uploadPendingTextures(RtPipeline pipeline, long sampler) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry != null) {
            invokeSceneSource(entry, "bindless texture upload", () -> {
                entry.source().uploadPendingTextures(pipeline, sampler);
                return null;
            });
        }
    }

    /** Collect each scene source transactionally into its provider-scoped geometry sink. */
    public void submitGeometry(Function<ResourceId, SceneGeometrySink> sinkFactory) {
        for (Map.Entry<ResourceId, SceneProvider> entry : scenes().entrySet()) {
            ProviderKey key = new ProviderKey("scene", entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            Map<Long, TriangleMesh> stagedMeshes = new java.util.LinkedHashMap<>();
            Map<Long, StagedInstance> stagedInstances = new java.util.LinkedHashMap<>();
            SceneGeometrySink stagingSink = new SceneGeometrySink() {
                @Override
                public void retainMesh(long meshKey, TriangleMesh mesh) {
                    if (stagedMeshes.putIfAbsent(meshKey, mesh) != null) {
                        throw new IllegalArgumentException("duplicate retained mesh key " + meshKey);
                    }
                }

                @Override
                public void instance(long instanceKey, long meshKey,
                                     GeometryTransform transform) {
                    if (stagedInstances.putIfAbsent(instanceKey, new StagedInstance(meshKey, transform)) != null) {
                        throw new IllegalArgumentException("duplicate geometry instance key " + instanceKey);
                    }
                }
            };
            try {
                entry.getValue().submitGeometry(stagingSink);
                for (StagedInstance instance : stagedInstances.values()) {
                    if (!stagedMeshes.containsKey(instance.meshKey)) {
                        throw new IllegalArgumentException("geometry instance references unsubmitted mesh "
                                + instance.meshKey);
                    }
                }
                SceneGeometrySink sink = sinkFactory.apply(entry.getKey());
                stagedMeshes.forEach(sink::retainMesh);
                stagedInstances.forEach((instanceKey, instance) ->
                        sink.instance(instanceKey, instance.meshKey, instance.transform));
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica scene provider {} failed and was disabled", entry.getKey(), t);
                stopOne("scene", entry, key, SceneProvider::stop);
            }
        }
    }

    private record StagedInstance(long meshKey, GeometryTransform transform) {
    }

    public void invalidateScenes() {
        invoke("scene", scenes(), SceneProvider::invalidate, SceneProvider::stop);
    }

    public void onResourceReload() {
        invoke("scene", scenes(), SceneProvider::onResourceReload, SceneProvider::stop);
        invoke("light", lights(), LightProvider::onResourceReload, LightProvider::stop);
        invoke("material", materials(), MaterialSource::onResourceReload, MaterialSource::stop);
    }

    /**
     * Collect one immutable material rule snapshot. A source stages into its own list so a failure cannot
     * publish a partial contribution; other sources remain active and retain their registration order.
     */
    public MaterialContributions collectMaterials() {
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
                    if (definedMaterials.contains(definition.id()) || !stagedIds.add(definition.id())) {
                        throw new IllegalStateException("duplicate material definition " + definition.id());
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

    private Map<ResourceId, SceneProvider> scenes() {
        return scenes != null ? scenes : CausticaApi.registry().sceneProviders();
    }

    private Map<ResourceId, LightProvider> lights() {
        return lights != null ? lights : CausticaApi.registry().lightProviders();
    }

    private Map<ResourceId, MaterialSource> materials() {
        return materials != null ? materials : CausticaApi.registry().materialSources();
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
                throw new IllegalStateException("multiple optimized scene sources are active: "
                        + selected.id() + " and " + entry.getKey());
            }
            selected = new SceneSourceEntry(entry.getKey(), entry, key, source);
        }
        return selected;
    }

    private SceneSourceEntry requireSceneSource(ResourceId id) {
        SceneSourceEntry entry = primarySceneSource();
        if (entry == null || !entry.id().equals(id)) {
            throw new IllegalStateException("optimized scene source is no longer active: " + id);
        }
        return entry;
    }

    private <T> T invokeSceneSource(SceneSourceEntry entry, String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (Throwable failure) {
            failed.add(entry.key());
            CausticaMod.LOGGER.error("Caustica scene provider {} failed during {} and was disabled",
                    entry.id(), operation, failure);
            stopOne("scene", entry.provider(), entry.key(), SceneProvider::stop);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(failure);
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

    private record SceneSourceEntry(ResourceId id, Map.Entry<ResourceId, SceneProvider> provider,
                                    ProviderKey key, RtSceneSource source) {
    }

    public record PrimaryScene(ResourceId provider, RtSceneSource.Retained retained) {
    }
}
