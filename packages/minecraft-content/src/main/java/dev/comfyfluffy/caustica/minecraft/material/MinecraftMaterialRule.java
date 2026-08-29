package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import java.util.Objects;

/** Ordered Minecraft resource-pack override applied while the epoch material table is built. */
public record MinecraftMaterialRule(ResourceId id, ResourceId material, ResourceId geometry,
                                    Parameters parameters) {
    public MinecraftMaterialRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(parameters, "parameters");
    }

    public boolean matches(ResourceId candidateMaterial, ResourceId candidateGeometry) {
        return material.equals(candidateMaterial)
                && (geometry == null || geometry.equals(candidateGeometry));
    }

    public record Parameters(Float specularRoughness, Float baseMetalness,
                             Float specularIor, Float transmissionWeight,
                             Float emissionLuminanceCdM2, MinecraftMaterialTopology topology) {
        public Parameters {
            unit("specularRoughness", specularRoughness);
            unit("baseMetalness", baseMetalness);
            unit("transmissionWeight", transmissionWeight);
            if (specularIor != null && (!Float.isFinite(specularIor) || specularIor <= 0.0f)) {
                throw new IllegalArgumentException("specularIor must be positive");
            }
            if (emissionLuminanceCdM2 != null && (!Float.isFinite(emissionLuminanceCdM2)
                    || emissionLuminanceCdM2 < 0.0f || emissionLuminanceCdM2 > 65504.0f)) {
                throw new IllegalArgumentException("emissionLuminanceCdM2 must be in [0,65504]");
            }
        }

        private static void unit(String name, Float value) {
            if (value != null && (!Float.isFinite(value) || value < 0.0f || value > 1.0f)) {
                throw new IllegalArgumentException(name + " must be in [0,1]");
            }
        }
    }
}
