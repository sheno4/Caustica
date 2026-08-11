package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.ProviderRegistration;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CausticaRegistry {
    private final Map<ResourceId, Feature> features = new LinkedHashMap<>();
    private final Map<ResourceId, CausticaRenderPass> renderPasses = new LinkedHashMap<>();
    private final Map<ResourceId, SceneProvider> sceneProviders = new LinkedHashMap<>();
    private final Map<ResourceId, LightProvider> lightProviders = new LinkedHashMap<>();
    private final Map<ResourceId, MaterialSource> materialSources = new LinkedHashMap<>();
    private final Map<Slot, ResourceId> defaults = new LinkedHashMap<>();
    private final Map<Slot, ResourceId> selected = new LinkedHashMap<>();
    /**
     * Registration order IS the ABI: a surface implementation's position here is the index materials pack
     * into their binding and the case the generated dispatch switch resolves. {@code caustica:builtin}
     * registers first, so index 0 is always the built-in surface, which is what a zero-initialised
     * binding and any unresolved name fall back to.
     */
    private final List<Feature.SurfaceImplementation> surfaces = new ArrayList<>();
    private final List<Feature.SurfaceModifierImplementation> surfaceModifiers = new ArrayList<>();

    public FeatureBuilder feature(ResourceId id) {
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
        requireUnique(sceneProviders, feature.sceneProviders(), "scene provider");
        requireUnique(lightProviders, feature.lightProviders(), "light provider");
        requireUnique(materialSources, feature.materialSources(), "material source");
        for (Feature.SurfaceImplementation surface : feature.surfaces()) {
            if (surfaceIndex(surface.id()) >= 0) {
                throw new IllegalStateException("duplicate surface implementation id " + surface.id());
            }
        }
        for (Feature.SurfaceModifierImplementation modifier : feature.surfaceModifiers()) {
            if (surfaceModifiers.stream().anyMatch(existing -> existing.id().equals(modifier.id()))) {
                throw new IllegalStateException("duplicate surface modifier id " + modifier.id());
            }
        }
        features.put(feature.id(), feature);
        surfaces.addAll(feature.surfaces());
        surfaceModifiers.addAll(feature.surfaceModifiers());
        for (CausticaRenderPass renderPass : feature.renderPasses()) {
            renderPasses.put(renderPass.id(), renderPass);
        }
        feature.sceneProviders().forEach(registration ->
                sceneProviders.put(registration.id(), registration.provider()));
        feature.lightProviders().forEach(registration ->
                lightProviders.put(registration.id(), registration.provider()));
        feature.materialSources().forEach(registration ->
                materialSources.put(registration.id(), registration.provider()));
    }

    public synchronized void setDefault(Slot slot, ResourceId featureId) {
        binding(slot, featureId);
        if (defaults.putIfAbsent(slot, featureId) != null) {
            throw new IllegalStateException("slot " + slot.id() + " already has a default binding");
        }
    }

    public synchronized void select(Slot slot, ResourceId featureId) {
        binding(slot, featureId);
        selected.put(slot, featureId);
    }

    public synchronized void selectDefault(Slot slot) {
        if (!defaults.containsKey(slot)) {
            throw new IllegalStateException("slot " + slot.id() + " has no default binding");
        }
        selected.remove(slot);
    }

    /**
     * Every feature that binds {@code slot}, the default first and the rest in registration order — the
     * choice a settings screen offers for that slot. A slot always has at least its default.
     */
    public synchronized List<ResourceId> candidates(Slot slot) {
        ResourceId defaultFeature = defaults.get(slot);
        List<ResourceId> candidates = new ArrayList<>();
        if (defaultFeature != null) {
            candidates.add(defaultFeature);
        }
        for (Feature feature : features.values()) {
            if (feature.bindings().containsKey(slot) && !feature.id().equals(defaultFeature)) {
                candidates.add(feature.id());
            }
        }
        return List.copyOf(candidates);
    }

    /** The binding used when nothing is selected — always a built-in, so a new extension changes nothing. */
    public synchronized ResourceId defaultFeature(Slot slot) {
        return defaults.get(slot);
    }

    /** What {@link #selection()} will resolve this slot to, whether that came from a choice or the default. */
    public synchronized ResourceId selectedFeature(Slot slot) {
        return selected.getOrDefault(slot, defaults.get(slot));
    }

    /**
     * Whether this slot is on its default rather than an explicit choice. {@link #selectedFeature} collapses
     * the two, but they differ to a screen: only an explicit choice is worth persisting, and only a default
     * is worth labelling as one.
     */
    public synchronized boolean isDefaultSelected(Slot slot) {
        return !selected.containsKey(slot);
    }

    public synchronized Map<ResourceId, Feature> features() {
        return Map.copyOf(features);
    }

    /** Every registered surface implementation, in the order that IS their compiled index. */
    public synchronized List<Feature.SurfaceImplementation> surfaces() {
        return List.copyOf(surfaces);
    }

    /** Every registered projected surface modifier, in deterministic application order. */
    public synchronized List<Feature.SurfaceModifierImplementation> surfaceModifiers() {
        return List.copyOf(surfaceModifiers);
    }

    /**
     * The compiled index of a surface implementation, or -1 when nothing registered that id. Materials
     * resolve their authored name through this once at compile time; the shader only ever sees the index.
     */
    public synchronized int surfaceIndex(ResourceId surfaceId) {
        for (int index = 0; index < surfaces.size(); index++) {
            if (surfaces.get(index).id().equals(surfaceId)) {
                return index;
            }
        }
        return -1;
    }

    public synchronized Map<ResourceId, CausticaRenderPass> renderPasses() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(renderPasses));
    }

    public synchronized Map<ResourceId, SceneProvider> sceneProviders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sceneProviders));
    }

    public synchronized Map<ResourceId, LightProvider> lightProviders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lightProviders));
    }

    public synchronized Map<ResourceId, MaterialSource> materialSources() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(materialSources));
    }

    private static <T> void requireUnique(Map<ResourceId, T> registered,
                                          Iterable<ProviderRegistration<T>> candidates, String kind) {
        for (ProviderRegistration<T> candidate : candidates) {
            if (registered.containsKey(candidate.id())) {
                throw new IllegalStateException("duplicate " + kind + " id " + candidate.id());
            }
        }
    }

    public synchronized Selection selection() {
        Map<Slot, SelectedBinding> bindings = new LinkedHashMap<>();
        for (Slot slot : Slots.ALL) {
            ResourceId featureId = selected.getOrDefault(slot, defaults.get(slot));
            if (featureId == null) {
                throw new IllegalStateException("slot " + slot.id() + " has no default binding");
            }
            Feature feature = features.get(featureId);
            bindings.put(slot, new SelectedBinding(feature, binding(slot, featureId)));
        }
        Map<ResourceId, Feature> owners = new LinkedHashMap<>();
        for (Feature.SurfaceImplementation surface : surfaces) {
            owners.put(surface.id(), features.get(surface.featureId()));
        }
        Map<ResourceId, Feature> modifierOwners = new LinkedHashMap<>();
        for (Feature.SurfaceModifierImplementation modifier : surfaceModifiers) {
            modifierOwners.put(modifier.id(), features.get(modifier.featureId()));
        }
        return new Selection(bindings, List.copyOf(surfaces), Map.copyOf(owners),
                List.copyOf(surfaceModifiers), Map.copyOf(modifierOwners));
    }

    private Feature.Binding binding(Slot slot, ResourceId featureId) {
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

    /**
     * What one world pipeline is compiled from: the feature bound to each slot, plus every registered
     * surface implementation in index order (the dispatch switch's cases) and the feature each came from
     * (whose shader source resolves its module).
     */
    public record Selection(Map<Slot, SelectedBinding> bindings,
                            List<Feature.SurfaceImplementation> surfaces,
                            Map<ResourceId, Feature> surfaceOwners,
                            List<Feature.SurfaceModifierImplementation> surfaceModifiers,
                            Map<ResourceId, Feature> surfaceModifierOwners) {
        public Selection {
            bindings = Map.copyOf(bindings);
            surfaces = List.copyOf(surfaces);
            surfaceOwners = Map.copyOf(surfaceOwners);
            surfaceModifiers = List.copyOf(surfaceModifiers);
            surfaceModifierOwners = Map.copyOf(surfaceModifierOwners);
            if (!bindings.keySet().containsAll(Slots.ALL)) {
                throw new IllegalArgumentException("selection must bind every engine slot");
            }
            if (surfaces.isEmpty()) {
                throw new IllegalArgumentException(
                        "selection needs at least the built-in surface implementation");
            }
        }

        public SelectedBinding binding(Slot slot) {
            return Objects.requireNonNull(bindings.get(slot), "unbound slot " + slot.id());
        }

        /** Every feature contributing Slang to this composition, whether through a slot or a surface. */
        public List<Feature> features() {
            List<Feature> features = new ArrayList<>();
            bindings.values().stream().map(SelectedBinding::feature)
                    .filter(feature -> !features.contains(feature)).forEach(features::add);
            surfaces.stream().map(surface -> surfaceOwners.get(surface.id()))
                    .filter(feature -> !features.contains(feature)).forEach(features::add);
            surfaceModifiers.stream().map(modifier -> surfaceModifierOwners.get(modifier.id()))
                    .filter(feature -> !features.contains(feature)).forEach(features::add);
            return List.copyOf(features);
        }
    }
}
