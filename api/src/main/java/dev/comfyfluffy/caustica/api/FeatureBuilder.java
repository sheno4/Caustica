package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.option.Option;
import dev.comfyfluffy.caustica.api.pass.PostEffectPass;
import dev.comfyfluffy.caustica.api.pass.WorldResourcePass;
import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.ui.UiPass;
import dev.comfyfluffy.caustica.api.scene.SceneProvider;

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
    private RuntimeActivation runtimeActivation = RuntimeActivation.ALWAYS;
    private final Map<Slot, Feature.Binding> bindings = new LinkedHashMap<>();
    private final List<Feature.SurfaceImplementation> surfaces = new ArrayList<>();
    private final List<Feature.EnvironmentImplementation> environments = new ArrayList<>();
    private final List<Feature.SurfaceModifierImplementation> surfaceModifiers = new ArrayList<>();
    private final List<Option<?>> options = new ArrayList<>();
    private final List<String> optionGroups = new ArrayList<>();
    private final List<RuntimeRegistration<WorldResourcePass>> worldResourcePasses = new ArrayList<>();
    private final List<RuntimeRegistration<PostEffectPass>> postEffectPasses = new ArrayList<>();
    private final List<RuntimeRegistration<UiPass>> uiPasses = new ArrayList<>();
    private final List<RuntimeRegistration<SceneProvider>> sceneProviders = new ArrayList<>();
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
     * Choose when this feature's runtime contributions are instantiated. The default is
     * {@link RuntimeActivation#ALWAYS}, which is what a feature wants when nothing selects it — a surface or
     * environment implementation is compiled into every composition and chosen per material or per scene,
     * so its passes cannot be gated on a selection that never happens.
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

    /**
     * Register an environment implementation a scene can name. Registered as a set, not bound to a slot:
     * every registered implementation is compiled into the composition at once and each scene selects one,
     * so a second scene can have its own sky.
     */
    public FeatureBuilder environment(ResourceId id, String module, String type) {
        Objects.requireNonNull(id, "id");
        if (environments.stream().anyMatch(existing -> existing.id().equals(id))) {
            throw new IllegalStateException(this.id + " declares duplicate environment implementation " + id);
        }
        environments.add(new Feature.EnvironmentImplementation(this.id, id, module, type));
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

    /**
     * Register a pass that produces something the world pipeline reads, recorded before the trace — see
     * {@link WorldResourcePass}.
     */
    public FeatureBuilder worldResourcePass(ResourceId passId,
                                            ContextualRuntimeFactory<? extends WorldResourcePass> factory) {
        worldResourcePasses.add(declare(worldResourcePasses, passId, factory, "world resource pass"));
        return this;
    }

    /**
     * Register a pass that transforms the scene image, recorded after reconstruction and before the
     * display transform — see {@link PostEffectPass}.
     */
    public FeatureBuilder postEffectPass(ResourceId passId,
                                         ContextualRuntimeFactory<? extends PostEffectPass> factory) {
        postEffectPasses.add(declare(postEffectPasses, passId, factory, "post effect pass"));
        return this;
    }

    /**
     * Register a pass that draws the separate UI layer after the display transform, once per rendered
     * frame. Presentation consumes the layer for every output frame; a frame-generation backend may reuse
     * it or interpolate it separately from the scene — see {@link UiPass}.
     */
    public FeatureBuilder uiPass(ResourceId passId, ContextualRuntimeFactory<? extends UiPass> factory) {
        uiPasses.add(declare(uiPasses, passId, factory, "UI pass"));
        return this;
    }

    private <P> RuntimeRegistration<P> declare(List<RuntimeRegistration<P>> declared, ResourceId passId,
                                            ContextualRuntimeFactory<? extends P> factory, String kind) {
        if (declared.stream().anyMatch(existing -> existing.id().equals(passId))) {
            throw new IllegalStateException(id + " declares duplicate " + kind + " " + passId);
        }
        return new RuntimeRegistration<>(passId, factory);
    }

    public FeatureBuilder sceneProvider(ResourceId providerId,
                                                   ContextualRuntimeFactory<? extends SceneProvider> factory) {
        RuntimeRegistration<SceneProvider> registration = new RuntimeRegistration<>(providerId, factory);
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
                surfaces, environments, surfaceModifiers, options, optionGroups, worldResourcePasses, postEffectPasses, uiPasses,
                sceneProviders, passResourceModules);
        registry.register(feature);
        return feature;
    }

    private boolean hasRuntimeContributions() {
        return !worldResourcePasses.isEmpty() || !postEffectPasses.isEmpty() || !uiPasses.isEmpty()
                || !sceneProviders.isEmpty();
    }

}
