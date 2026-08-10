package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record Feature(Identifier id, Component title, Component description, FeatureCategory category,
                      ShaderSource shaderSource, Map<Slot, Binding> bindings,
                      List<SurfaceImplementation> surfaces, List<Option<?>> options,
                      List<String> optionGroups,
                      List<CausticaRenderPass> renderPasses, List<SceneProvider> sceneProviders,
                      List<LightProvider> lightProviders, List<MaterialSource> materialSources,
                      List<String> passResourceModules) {
    public Feature {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
        bindings = Map.copyOf(bindings);
        surfaces = List.copyOf(surfaces);
        options = List.copyOf(options);
        optionGroups = List.copyOf(optionGroups);
        renderPasses = List.copyOf(renderPasses);
        sceneProviders = List.copyOf(sceneProviders);
        lightProviders = List.copyOf(lightProviders);
        materialSources = List.copyOf(materialSources);
        passResourceModules = List.copyOf(passResourceModules);
        if (!bindings.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(id + ": a feature with slot bindings needs a shader source");
        }
        if (!surfaces.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(
                    id + ": a feature with surface implementations needs a shader source");
        }
        if (!passResourceModules.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(id + ": a feature with pass resource modules needs a shader source");
        }
        for (Binding binding : bindings.values()) {
            if (!binding.featureId().equals(id)) {
                throw new IllegalArgumentException("binding owner does not match feature " + id);
            }
        }
        Set<String> headed = new HashSet<>();
        for (Option<?> option : options) {
            // Rejected here rather than where the value is read: the options store resolves every declared
            // option while loading, so an unsupported kind would otherwise surface as a crash during mod
            // init, naming the option but not the reason. Lifting this needs a runtime type token on
            // Option that generic storage code can deserialize an enum or validate a colour against.
            if (option.kind() != Option.Kind.BOOL && option.kind() != Option.Kind.RANGE) {
                throw new IllegalArgumentException(id + ": option " + option.id() + " is "
                        + option.kind() + ", but the options store backs only BOOL and RANGE");
            }
            String group = option.group();
            if (group == null) {
                continue;
            }
            if (!optionGroups.contains(group)) {
                throw new IllegalArgumentException(
                        id + ": option " + option.id() + " is in undeclared group " + group);
            }
            if (option.isGroupHeader() && !headed.add(group)) {
                throw new IllegalArgumentException(id + ": group " + group + " has more than one header");
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

    /**
     * A Slang type implementing {@code ISurfaceModel}, named by {@code id} so a material can select it.
     * Unlike a slot binding these do not compete: every registered implementation is compiled into the
     * composition at once and each material names the one it wants.
     */
    public record SurfaceImplementation(Identifier featureId, Identifier id, String module, String type) {
        public SurfaceImplementation {
            Objects.requireNonNull(featureId, "featureId");
            Objects.requireNonNull(id, "id");
            Slot.requireSlangIdentifier(module, "module");
            Slot.requireSlangIdentifier(type, "type");
        }
    }
}
