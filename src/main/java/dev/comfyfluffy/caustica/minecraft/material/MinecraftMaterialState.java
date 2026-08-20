package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.FeatureRuntimeContext;
import dev.comfyfluffy.caustica.api.ResourceId;

/** Activation-scoped publication point shared by Minecraft's material and scene providers. */
public final class MinecraftMaterialState {
    public static final FeatureRuntimeContext.Key<MinecraftMaterialState> CONTEXT_KEY =
            new FeatureRuntimeContext.Key<>(ResourceId.of("caustica", "minecraft_material"),
                    MinecraftMaterialState.class);
    private volatile MinecraftMaterialSnapshot snapshot = MinecraftMaterialSnapshot.empty();
    public MinecraftMaterialSnapshot snapshot() { return snapshot; }
    public void publish(MinecraftMaterialSnapshot snapshot) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
    }
}
