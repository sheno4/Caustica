package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
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
    private final Map<Slot, Feature.Binding> bindings = new LinkedHashMap<>();
    private final List<Feature.SurfaceImplementation> surfaces = new ArrayList<>();
    private final List<Option<?>> options = new ArrayList<>();
    private final List<String> optionGroups = new ArrayList<>();
    private final List<CausticaRenderPass> renderPasses = new ArrayList<>();
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

    public FeatureBuilder bind(Slot slot, String module, String type) {
        Feature.Binding binding = new Feature.Binding(id, slot, module, type);
        if (bindings.putIfAbsent(slot, binding) != null) {
            throw new IllegalStateException(id + " binds slot " + slot.id() + " more than once");
        }
        return this;
    }

    /**
     * Register a Slang {@code ISurfaceModel} implementation under {@code id}. Surface implementations do
     * not compete for a slot: every registered one is compiled into the composition and a material
     * selects the one it wants by this id, so a mod adding one changes nothing about what any other
     * material renders as.
     */
    public FeatureBuilder surface(ResourceId id, String module, String type) {
        Objects.requireNonNull(id, "id");
        if (surfaces.stream().anyMatch(existing -> existing.id().equals(id))) {
            throw new IllegalStateException(this.id + " declares duplicate surface implementation " + id);
        }
        surfaces.add(new Feature.SurfaceImplementation(this.id, id, module, type));
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

    public FeatureBuilder renderPass(CausticaRenderPass renderPass) {
        Objects.requireNonNull(renderPass, "renderPass");
        if (renderPasses.stream().anyMatch(existing -> existing.id().equals(renderPass.id()))) {
            throw new IllegalStateException(id + " declares duplicate render pass " + renderPass.id());
        }
        renderPasses.add(renderPass);
        return this;
    }

    public FeatureBuilder sceneProvider(ResourceId providerId, SceneProvider provider) {
        ProviderRegistration<SceneProvider> registration = new ProviderRegistration<>(providerId, provider);
        if (sceneProviders.stream().anyMatch(existing -> existing.id().equals(providerId))) {
            throw new IllegalStateException(id + " declares duplicate scene provider " + providerId);
        }
        sceneProviders.add(registration);
        return this;
    }

    public FeatureBuilder lightProvider(ResourceId providerId, LightProvider provider) {
        ProviderRegistration<LightProvider> registration = new ProviderRegistration<>(providerId, provider);
        if (lightProviders.stream().anyMatch(existing -> existing.id().equals(providerId))) {
            throw new IllegalStateException(id + " declares duplicate light provider " + providerId);
        }
        lightProviders.add(registration);
        return this;
    }

    public FeatureBuilder materialSource(ResourceId sourceId, MaterialSource source) {
        ProviderRegistration<MaterialSource> registration = new ProviderRegistration<>(sourceId, source);
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
        registered = true;
        DisplayText resolvedTitle = title != null ? title : DisplayText.literal(id.toString());
        Feature feature = new Feature(id, resolvedTitle, description, category, shaderSource, bindings,
                surfaces, options, optionGroups, renderPasses, sceneProviders, lightProviders,
                materialSources, passResourceModules);
        registry.register(feature);
        return feature;
    }

}
