package dev.comfyfluffy.caustica.api;

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

    public Feature register() {
        if (registered) {
            throw new IllegalStateException(id + " is already registered");
        }
        registered = true;
        Component resolvedTitle = title != null ? title
                : Component.literal(id.getNamespace() + ':' + id.getPath());
        Feature feature = new Feature(id, resolvedTitle, category, shaderSource, bindings, options);
        registry.register(feature);
        return feature;
    }
}
