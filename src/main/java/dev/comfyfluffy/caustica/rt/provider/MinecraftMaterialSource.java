package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import net.minecraft.resources.Identifier;

public final class MinecraftMaterialSource implements MaterialSource {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "minecraft_materials");

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public void shutdown() {
        RtBlockMaterials.INSTANCE.destroy();
    }
}
