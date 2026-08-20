package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.FeatureRuntimeContext;
import dev.comfyfluffy.caustica.api.ResourceId;

/** Activation-scoped publication point shared by Minecraft's material and scene providers. */
public final class MinecraftMaterialEmissionState {
    public static final FeatureRuntimeContext.Key<MinecraftMaterialEmissionState> CONTEXT_KEY =
            new FeatureRuntimeContext.Key<>(ResourceId.of("caustica", "minecraft_material_emission"),
                    MinecraftMaterialEmissionState.class);

    private volatile MinecraftMaterialEmissionSnapshot snapshot = MinecraftMaterialEmissionSnapshot.empty();

    public MinecraftMaterialEmissionSnapshot snapshot() {
        return snapshot;
    }

    public void publish(MinecraftMaterialEmissionSnapshot snapshot) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
    }
}
