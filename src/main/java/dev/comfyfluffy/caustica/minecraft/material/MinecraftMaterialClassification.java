package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import java.util.Objects;

/** Minecraft block semantics used to select one session material-table entry. */
public record MinecraftMaterialClassification(ResourceId geometry, MinecraftMaterialProfile profile) {
    public MinecraftMaterialClassification {
        Objects.requireNonNull(profile, "profile");
    }
}
