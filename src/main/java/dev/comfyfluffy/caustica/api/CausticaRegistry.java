package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public final class CausticaRegistry {
    private final Map<Identifier, Feature> features = new LinkedHashMap<>();
    private final Map<Identifier, CausticaRenderPass> renderPasses = new LinkedHashMap<>();
    private final Map<Identifier, SceneProvider> sceneProviders = new LinkedHashMap<>();
    private final Map<Identifier, LightProvider> lightProviders = new LinkedHashMap<>();
    private final Map<Identifier, MaterialSource> materialSources = new LinkedHashMap<>();
    private final Map<Slot, Identifier> defaults = new LinkedHashMap<>();
    private final Map<Slot, Identifier> selected = new LinkedHashMap<>();

    public static CausticaRegistry withBuiltins() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        return registry;
    }

    public FeatureBuilder feature(Identifier id) {
        Objects.requireNonNull(id, "id");
        return new FeatureBuilder(this, id);
    }

    synchronized void register(Feature feature) {
        if (features.containsKey(feature.id())) {
            throw new IllegalStateException("duplicate feature id " + feature.id());
        }
        for (CausticaRenderPass renderPass : feature.renderPasses()) {
            if (renderPasses.containsKey(renderPass.id())) {
                throw new IllegalStateException("duplicate render pass id " + renderPass.id());
            }
        }
        requireUnique(sceneProviders, feature.sceneProviders(), SceneProvider::id, "scene provider");
        requireUnique(lightProviders, feature.lightProviders(), LightProvider::id, "light provider");
        requireUnique(materialSources, feature.materialSources(), MaterialSource::id, "material source");
        features.put(feature.id(), feature);
        for (CausticaRenderPass renderPass : feature.renderPasses()) {
            renderPasses.put(renderPass.id(), renderPass);
        }
        feature.sceneProviders().forEach(provider -> sceneProviders.put(provider.id(), provider));
        feature.lightProviders().forEach(provider -> lightProviders.put(provider.id(), provider));
        feature.materialSources().forEach(source -> materialSources.put(source.id(), source));
    }

    synchronized void setDefault(Slot slot, Identifier featureId) {
        binding(slot, featureId);
        if (defaults.putIfAbsent(slot, featureId) != null) {
            throw new IllegalStateException("slot " + slot.id() + " already has a default binding");
        }
    }

    public synchronized void select(Slot slot, Identifier featureId) {
        binding(slot, featureId);
        selected.put(slot, featureId);
    }

    public synchronized void selectDefault(Slot slot) {
        if (!defaults.containsKey(slot)) {
            throw new IllegalStateException("slot " + slot.id() + " has no default binding");
        }
        selected.remove(slot);
    }

    public synchronized Map<Identifier, Feature> features() {
        return Map.copyOf(features);
    }

    public synchronized Map<Identifier, CausticaRenderPass> renderPasses() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(renderPasses));
    }

    public synchronized Map<Identifier, SceneProvider> sceneProviders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sceneProviders));
    }

    public synchronized Map<Identifier, LightProvider> lightProviders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lightProviders));
    }

    public synchronized Map<Identifier, MaterialSource> materialSources() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(materialSources));
    }

    private static <T> void requireUnique(Map<Identifier, T> registered, Iterable<T> candidates,
                                          java.util.function.Function<T, Identifier> id, String kind) {
        for (T candidate : candidates) {
            Identifier candidateId = id.apply(candidate);
            if (registered.containsKey(candidateId)) {
                throw new IllegalStateException("duplicate " + kind + " id " + candidateId);
            }
        }
    }

    public synchronized Selection selection() {
        Map<Slot, SelectedBinding> bindings = new LinkedHashMap<>();
        for (Slot slot : Slots.ALL) {
            Identifier featureId = selected.getOrDefault(slot, defaults.get(slot));
            if (featureId == null) {
                throw new IllegalStateException("slot " + slot.id() + " has no default binding");
            }
            Feature feature = features.get(featureId);
            bindings.put(slot, new SelectedBinding(feature, binding(slot, featureId)));
        }
        return new Selection(bindings);
    }

    private Feature.Binding binding(Slot slot, Identifier featureId) {
        Feature feature = features.get(featureId);
        if (feature == null) {
            throw new IllegalArgumentException("unknown feature " + featureId);
        }
        Feature.Binding binding = feature.bindings().get(slot);
        if (binding == null) {
            throw new IllegalArgumentException(featureId + " does not bind slot " + slot.id());
        }
        return binding;
    }

    public record SelectedBinding(Feature feature, Feature.Binding binding) {
        public SelectedBinding {
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(binding, "binding");
        }
    }

    public record Selection(Map<Slot, SelectedBinding> bindings) {
        public Selection {
            bindings = Map.copyOf(bindings);
            if (!bindings.keySet().containsAll(Slots.ALL)) {
                throw new IllegalArgumentException("selection must bind every engine slot");
            }
        }

        public SelectedBinding binding(Slot slot) {
            return Objects.requireNonNull(bindings.get(slot), "unbound slot " + slot.id());
        }
    }
}
