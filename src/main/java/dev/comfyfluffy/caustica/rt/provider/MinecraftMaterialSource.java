package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;

public final class MinecraftMaterialSource implements MaterialSource {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_materials");

    @Override
    public void shutdown() {
        RtBlockMaterials.INSTANCE.destroy();
    }
}
