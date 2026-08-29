package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;

/** Stable Minecraft material identities shared by extraction and program resources. */
public final class MinecraftMaterialIds {
    public static final ResourceId WATER = ResourceId.of("minecraft", "water");
    public static final ResourceId LAVA = ResourceId.of("minecraft", "block/lava_still");

    private MinecraftMaterialIds() { }
}
