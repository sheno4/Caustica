package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;

import java.util.Objects;

/**
 * Complete shading, light-extraction, and opacity semantics for one Minecraft material key. A resolver
 * replacement must preserve the fallback definition's canonical handle. Definition provider data is read
 * by the selected surface shader, not its coverage shader; custom coverage is limited to the generic base
 * texture, UV, tint, and vertex-color facts supplied through {@code CoverageInput}.
 */
public record MinecraftMaterialResolution(MaterialDefinition definition,
                                          MinecraftMaterialEmission emission,
                                          SceneMesh.OpacityMicromapRange opacityMicromapRange) {
    public MinecraftMaterialResolution {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(emission, "emission");
    }
}
