package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.option.Option;
import dev.comfyfluffy.caustica.api.pass.PostEffectPass;
import dev.comfyfluffy.caustica.api.pass.WorldResourcePass;
import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.ui.UiPass;
import dev.comfyfluffy.caustica.api.scene.SceneProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record Feature(ResourceId id, DisplayText title, DisplayText description, FeatureCategory category,
                      ShaderSource shaderSource, RuntimeActivation runtimeActivation, Map<Slot, Binding> bindings,
                      List<SurfaceImplementation> surfaces,
                      List<EnvironmentImplementation> environments,
                      List<SurfaceModifierImplementation> surfaceModifiers,
                      List<Option<?>> options,
                      List<String> optionGroups,
                      List<RuntimeRegistration<WorldResourcePass>> worldResourcePasses,
                      List<RuntimeRegistration<PostEffectPass>> postEffectPasses,
                      List<RuntimeRegistration<UiPass>> uiPasses,
                      List<RuntimeRegistration<SceneProvider>> sceneProviders,
                      List<String> passResourceModules) {
    public Feature {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(runtimeActivation, "runtimeActivation");
        bindings = Map.copyOf(bindings);
        surfaces = List.copyOf(surfaces);
        environments = List.copyOf(environments);
        surfaceModifiers = List.copyOf(surfaceModifiers);
        options = List.copyOf(options);
        optionGroups = List.copyOf(optionGroups);
        worldResourcePasses = List.copyOf(worldResourcePasses);
        postEffectPasses = List.copyOf(postEffectPasses);
        uiPasses = List.copyOf(uiPasses);
        sceneProviders = List.copyOf(sceneProviders);
        passResourceModules = List.copyOf(passResourceModules);
        if (!bindings.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(id + ": a feature with slot bindings needs a shader source");
        }
        if (!surfaces.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(
                    id + ": a feature with surface implementations needs a shader source");
        }
        if (!environments.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(
                    id + ": a feature with environment implementations needs a shader source");
        }
        if (!surfaceModifiers.isEmpty() && shaderSource == null) {
            throw new IllegalArgumentException(
                    id + ": a feature with surface modifiers needs a shader source");
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

    public record Binding(ResourceId featureId, Slot slot, String module, String type) {
        public Binding {
            Objects.requireNonNull(featureId, "featureId");
            Objects.requireNonNull(slot, "slot");
            Slot.requireSlangIdentifier(module, "module");
            Slot.requireSlangIdentifier(type, "type");
        }
    }

    /**
     * Separately named Slang implementations for shading and narrow coverage evaluation, selected by the
     * same material index. Unlike a slot binding these do not compete: every registered implementation is
     * compiled into the composition at once and each material names the one it wants.
     */
    public record SurfaceImplementation(ResourceId featureId, ResourceId id, String module, String type,
                                        ResourceId coverageId, String coverageModule, String coverageType) {
        public SurfaceImplementation {
            Objects.requireNonNull(featureId, "featureId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(coverageId, "coverageId");
            Slot.requireSlangIdentifier(module, "module");
            Slot.requireSlangIdentifier(type, "type");
            Slot.requireSlangIdentifier(coverageModule, "coverageModule");
            Slot.requireSlangIdentifier(coverageType, "coverageType");
        }
    }

    /**
     * A Slang {@code IEnvironmentModel} implementation. Registered as a set rather than bound to a slot,
     * for the reason surfaces are: a scene names the one it wants, so they do not compete, and two scenes
     * traced by one program can have different skies.
     */
    public record EnvironmentImplementation(ResourceId featureId, ResourceId id,
                                            String module, String type) {
        public EnvironmentImplementation {
            Objects.requireNonNull(featureId, "featureId");
            Objects.requireNonNull(id, "id");
            Slot.requireSlangIdentifier(module, "module");
            Slot.requireSlangIdentifier(type, "type");
        }
    }

    /**
     * A projected, geometry-opted-in edit to the final OpenPBR material description. Every registered
     * modifier runs in registration order; geometry without the receiver semantic pays for no dispatch.
     */
    public record SurfaceModifierImplementation(ResourceId featureId, ResourceId id,
                                                String module, String type) {
        public SurfaceModifierImplementation {
            Objects.requireNonNull(featureId, "featureId");
            Objects.requireNonNull(id, "id");
            Slot.requireSlangIdentifier(module, "module");
            Slot.requireSlangIdentifier(type, "type");
        }
    }
}
