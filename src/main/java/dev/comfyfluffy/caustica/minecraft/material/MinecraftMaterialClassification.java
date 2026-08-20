package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialProfile;

import java.util.Objects;

/** Minecraft block semantics used to select one provider-owned named material. */
public record MinecraftMaterialClassification(ResourceId geometry, MinecraftMaterialProfile profile) {
    public MinecraftMaterialClassification {
        Objects.requireNonNull(profile, "profile");
    }
}
