package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.RenderPassRegistration;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.ProviderRegistration;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FeatureBuilder {
    private final CausticaRegistry registry;
    private final ResourceId id;
    private DisplayText title;
    private DisplayText description = DisplayText.EMPTY;
    private FeatureCategory category = FeatureCategory.GENERAL;
    private ShaderSource shaderSource;
    private RuntimeActivation runtimeActivation = RuntimeActivation.SELECTED_SLOT;
    private final Map<Slot, Feature.Binding> bindings = new LinkedHashMap<>();
    private final List<Feature.SurfaceImplementation> surfaces = new ArrayList<>();
    private final List<Feature.SurfaceModifierImplementation> surfaceModifiers = new ArrayList<>();
    private final List<Option<?>> options = new ArrayList<>();
    private final List<String> optionGroups = new ArrayList<>();
    private final List<RenderPassRegistration> renderPasses = new ArrayList<>();
    private final List<ProviderRegistration<SceneProvider>> sceneProviders = new ArrayList<>();
    private final List<ProviderRegistration<LightProvider>> lightProviders = new ArrayList<>();
    private final List<ProviderRegistration<MaterialSource>> materialSources = new ArrayList<>();
    private final List<String> passResourceModules = new ArrayList<>();
    private boolean registered;

    FeatureBuilder(CausticaRegistry registry, ResourceId id) {
        this.registry = registry;
        this.id = id;
    }

    public FeatureBuilder title(DisplayText title) {
        this.title = Objects.requireNonNull(title, "title");
        return this;
    }

    /** One line describing what this feature does, shown beside its title where a slot offers a choice. */
    public FeatureBuilder description(DisplayText description) {
        this.description = Objects.requireNonNull(description, "description");
        return this;
    }

    public FeatureBuilder category(FeatureCategory category) {
        this.category = Objects.requireNonNull(category, "category");
        return this;
    }

    public FeatureBuilder shaderSource(ShaderSource shaderSource) {
        this.shaderSource = Objects.requireNonNull(shaderSource, "shaderSource");
        return this;
    }

    /**
     * Choose when this feature's runtime contributions are instantiated. {@link RuntimeActivation#SELECTED_SLOT}
     * requires this feature to bind at least one slot when it declares a render pass, scene provider, light
     * provider or material source. Composition-only surfaces, surface modifiers and options need no slot binding.
     */
    public FeatureBuilder runtimeActivation(RuntimeActivation runtimeActivation) {
        this.runtimeActivation = Objects.requireNonNull(runtimeActivation, "runtimeActivation");
        return this;
    }

    public FeatureBuilder bind(Slot slot, String module, String type) {
        Feature.Binding binding = new Feature.Binding(id, slot, module, type);
        if (bindings.putIfAbsent(slot, binding) != null) {
            throw new IllegalStateException(id + " binds slot " + slot.id() + " more than once");
        }
        return this;
    }

    /**
     * Register shading and coverage implementations selected by the same material implementation index.
     * Both implementations are required: closest-hit evaluates the surface type while any-hit and opacity
     * micromap classification evaluate the separately named, narrow coverage type.
     */
    public FeatureBuilder surface(ResourceId id, String module, String type,
                                  ResourceId coverageId, String coverageModule, String coverageType) {
        Objects.requireNonNull(id, "id");
        if (surfaces.stream().anyMatch(existing -> existing.id().equals(id))) {
            throw new IllegalStateException(this.id + " declares duplicate surface implementation " + id);
        }
        surfaces.add(new Feature.SurfaceImplementation(this.id, id, module, type,
                coverageId, coverageModule, coverageType));
        return this;
    }

    public FeatureBuilder option(Option<?> option) {
        Objects.requireNonNull(option, "option");
        if (options.stream().anyMatch(existing -> existing.id().equals(option.id()))) {
            throw new IllegalStateException(id + " declares duplicate option " + option.id());
        }
        options.add(option);
        return this;
    }

    /** Convenience for a component that publishes its own options as one list — see {@code BloomPass}. */
    public FeatureBuilder options(List<Option<?>> declared) {
        declared.forEach(this::option);
        return this;
    }

    /**
     * Declares a collapsible option group, in the order a settings screen should show them. An option joins
     * one with {@link Option#in}; the group's own title comes from its translation key, so the id is all the
     * engine needs.
     */
    public FeatureBuilder group(String id) {
        Option.requireGroupId(id);
        if (optionGroups.contains(id)) {
            throw new IllegalStateException(this.id + " declares duplicate option group " + id);
        }
        optionGroups.add(id);
        return this;
    }

    public FeatureBuilder renderPass(ResourceId passId, RenderStage stage,
                                     RuntimeFactory<? extends CausticaRenderPass> factory) {
        return renderPassContextual(passId, stage, ContextualRuntimeFactory.from(factory));
    }

    /** Register a pass factory that shares this feature's activation context with its other contributions. */
    public FeatureBuilder renderPassContextual(ResourceId passId, RenderStage stage,
                                               ContextualRuntimeFactory<? extends CausticaRenderPass> factory) {
        RenderPassRegistration registration = new RenderPassRegistration(passId, stage, factory);
        if (renderPasses.stream().anyMatch(existing -> existing.id().equals(passId))) {
            throw new IllegalStateException(id + " declares duplicate render pass " + passId);
        }
        renderPasses.add(registration);
        return this;
    }

    public FeatureBuilder sceneProvider(ResourceId providerId,
                                        RuntimeFactory<? extends SceneProvider> factory) {
        return sceneProviderContextual(providerId, ContextualRuntimeFactory.from(factory));
    }

    /** Register a scene factory that shares this feature's activation context with its other contributions. */
    public FeatureBuilder sceneProviderContextual(ResourceId providerId,
                                                  ContextualRuntimeFactory<? extends SceneProvider> factory) {
        ProviderRegistration<SceneProvider> registration = new ProviderRegistration<>(providerId, factory);
        if (sceneProviders.stream().anyMatch(existing -> existing.id().equals(providerId))) {
            throw new IllegalStateException(id + " declares duplicate scene provider " + providerId);
        }
        sceneProviders.add(registration);
        return this;
    }

    /** Register one projected surface modifier. Modifiers compose in registration order. */
    public FeatureBuilder surfaceModifier(ResourceId id, String module, String type) {
        Objects.requireNonNull(id, "id");
        if (surfaceModifiers.stream().anyMatch(existing -> existing.id().equals(id))) {
            throw new IllegalStateException(this.id + " declares duplicate surface modifier " + id);
        }
        surfaceModifiers.add(new Feature.SurfaceModifierImplementation(this.id, id, module, type));
        return this;
    }

    public FeatureBuilder lightProvider(ResourceId providerId,
                                        RuntimeFactory<? extends LightProvider> factory) {
        return lightProviderContextual(providerId, ContextualRuntimeFactory.from(factory));
    }

    /** Register a light factory that shares this feature's activation context with its other contributions. */
    public FeatureBuilder lightProviderContextual(ResourceId providerId,
                                                  ContextualRuntimeFactory<? extends LightProvider> factory) {
        ProviderRegistration<LightProvider> registration = new ProviderRegistration<>(providerId, factory);
        if (lightProviders.stream().anyMatch(existing -> existing.id().equals(providerId))) {
            throw new IllegalStateException(id + " declares duplicate light provider " + providerId);
        }
        lightProviders.add(registration);
        return this;
    }

    public FeatureBuilder materialSource(ResourceId sourceId,
                                         RuntimeFactory<? extends MaterialSource> factory) {
        return materialSourceContextual(sourceId, ContextualRuntimeFactory.from(factory));
    }

    /** Register a material factory that shares this feature's activation context with its other contributions. */
    public FeatureBuilder materialSourceContextual(ResourceId sourceId,
                                                   ContextualRuntimeFactory<? extends MaterialSource> factory) {
        ProviderRegistration<MaterialSource> registration = new ProviderRegistration<>(sourceId, factory);
        if (materialSources.stream().anyMatch(existing -> existing.id().equals(sourceId))) {
            throw new IllegalStateException(id + " declares duplicate material source " + sourceId);
        }
        materialSources.add(registration);
        return this;
    }

    /**
     * Declare a Slang module (by module name, not slot) this feature wants anchored outside the generic
     * composition mechanism — needed by any module a pass's own binding declarations live in (e.g. the
     * active sky implementation's bindings module), since Slang forbids a generic entry point's
     * type-parameter implementation from declaring global shader parameters itself. The engine imports every registered
     * feature's declared modules into one generated anchor module every composition-generic engine stage
     * (e.g. {@code sky_miss.slang}) imports unconditionally, so the pass never needs the engine to know
     * its resource names — only that this module exists.
     */
    public FeatureBuilder passResourceModule(String moduleName) {
        Slot.requireSlangIdentifier(moduleName, "moduleName");
        if (!passResourceModules.contains(moduleName)) {
            passResourceModules.add(moduleName);
        }
        return this;
    }

    public Feature register() {
        if (registered) {
            throw new IllegalStateException(id + " is already registered");
        }
        if (runtimeActivation == RuntimeActivation.SELECTED_SLOT && bindings.isEmpty()
                && hasRuntimeContributions()) {
            throw new IllegalStateException(id + " uses SELECTED_SLOT runtime activation and declares runtime "
                    + "contributions, but binds no slot; bind a slot or use ALWAYS runtime activation");
        }
        registered = true;
        DisplayText resolvedTitle = title != null ? title : DisplayText.literal(id.toString());
        Feature feature = new Feature(id, resolvedTitle, description, category, shaderSource, runtimeActivation, bindings,
                surfaces, surfaceModifiers, options, optionGroups, renderPasses, sceneProviders, lightProviders,
                materialSources, passResourceModules);
        registry.register(feature);
        return feature;
    }

    private boolean hasRuntimeContributions() {
        return !renderPasses.isEmpty() || !sceneProviders.isEmpty() || !lightProviders.isEmpty()
                || !materialSources.isEmpty();
    }

}
