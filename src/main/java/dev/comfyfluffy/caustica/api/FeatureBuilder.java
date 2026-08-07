package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FeatureBuilder {
    private final CausticaRegistry registry;
    private final Identifier id;
    private Component title;
    private FeatureCategory category = FeatureCategory.GENERAL;
    private ShaderSource shaderSource;
    private final Map<Slot, Feature.Binding> bindings = new LinkedHashMap<>();
    private final List<Option<?>> options = new ArrayList<>();
    private final List<CausticaRenderPass> renderPasses = new ArrayList<>();
    private final List<SceneProvider> sceneProviders = new ArrayList<>();
    private final List<LightProvider> lightProviders = new ArrayList<>();
    private final List<MaterialSource> materialSources = new ArrayList<>();
    private final List<String> passResourceModules = new ArrayList<>();
    private boolean registered;

    FeatureBuilder(CausticaRegistry registry, Identifier id) {
        this.registry = registry;
        this.id = id;
    }

    public FeatureBuilder title(Component title) {
        this.title = Objects.requireNonNull(title, "title");
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

    public FeatureBuilder renderPass(CausticaRenderPass renderPass) {
        Objects.requireNonNull(renderPass, "renderPass");
        if (renderPasses.stream().anyMatch(existing -> existing.id().equals(renderPass.id()))) {
            throw new IllegalStateException(id + " declares duplicate render pass " + renderPass.id());
        }
        renderPasses.add(renderPass);
        return this;
    }

    public FeatureBuilder sceneProvider(SceneProvider provider) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(provider.id(), "provider.id()");
        if (sceneProviders.stream().anyMatch(existing -> existing.id().equals(provider.id()))) {
            throw new IllegalStateException(id + " declares duplicate scene provider " + provider.id());
        }
        sceneProviders.add(provider);
        return this;
    }

    public FeatureBuilder lightProvider(LightProvider provider) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(provider.id(), "provider.id()");
        if (lightProviders.stream().anyMatch(existing -> existing.id().equals(provider.id()))) {
            throw new IllegalStateException(id + " declares duplicate light provider " + provider.id());
        }
        lightProviders.add(provider);
        return this;
    }

    public FeatureBuilder materialSource(MaterialSource source) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(source.id(), "source.id()");
        if (materialSources.stream().anyMatch(existing -> existing.id().equals(source.id()))) {
            throw new IllegalStateException(id + " declares duplicate material source " + source.id());
        }
        materialSources.add(source);
        return this;
    }

    /**
     * Declare a Slang module (by module name, not slot) this feature wants anchored outside the generic
     * composition mechanism — needed by any module a pass's own binding declarations live in (e.g.
     * {@code caustica_sky_bindings.slang}), since Slang forbids a generic entry point's type-parameter
     * implementation from declaring global shader parameters itself. The engine imports every registered
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
        Component resolvedTitle = title != null ? title
                : Component.literal(id.getNamespace() + ':' + id.getPath());
        Feature feature = new Feature(id, resolvedTitle, category, shaderSource, bindings, options, renderPasses,
                sceneProviders, lightProviders, materialSources, passResourceModules);
        registry.register(feature);
        return feature;
    }

}
