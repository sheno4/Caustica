package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;

import java.util.Objects;

/** Complete Minecraft-owned selector for one submitted named material definition. */
public record MinecraftMaterialKey(ResourceId material, ResourceId geometry,
                                   MinecraftMaterialProfile profile, MaterialTopology topology) {
    public MinecraftMaterialKey {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(topology, "topology");
    }

    public MinecraftMaterialKey defaultGeometry() {
        return geometry == null ? this : new MinecraftMaterialKey(material, null, profile, topology);
    }
}
