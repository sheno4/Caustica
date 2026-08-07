package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Feature(Identifier id, Component title, FeatureCategory category, ShaderSource shaderSource,
                      Map<Slot, Binding> bindings, List<Option<?>> options,
                      List<CausticaRenderPass> renderPasses, List<SceneProvider> sceneProviders,
                      List<LightProvider> lightProviders, List<MaterialSource> materialSources,
                      List<String> passResourceModules) {
    public Feature {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(category, "category");
        bindings = Map.copyOf(bindings);
        options = List.copyOf(options);
        renderPasses = List.copyOf(renderPasses);
        sceneProviders = List.copyOf(sceneProviders);
        lightProviders = List.copyOf(lightProviders);
        materialSources = List.copyOf(materialSources);
        passResourceModules = List.copyOf(passResourceModules);
        if (!bindings.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(id + ": a feature with slot bindings needs a shader source");
        }
        if (!passResourceModules.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(id + ": a feature with pass resource modules needs a shader source");
        }
        for (Binding binding : bindings.values()) {
            if (!binding.featureId().equals(id)) {
                throw new IllegalArgumentException("binding owner does not match feature " + id);
            }
        }
    }

    public record Binding(Identifier featureId, Slot slot, String module, String type) {
        public Binding {
            Objects.requireNonNull(featureId, "featureId");
            Objects.requireNonNull(slot, "slot");
            Slot.requireSlangIdentifier(module, "module");
            Slot.requireSlangIdentifier(type, "type");
        }
    }
}
