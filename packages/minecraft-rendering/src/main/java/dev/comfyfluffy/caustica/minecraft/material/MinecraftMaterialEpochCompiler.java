package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialRule;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.List;

/** Compiles one immutable CPU material lookup from the active Minecraft resource-pack epoch. */
@FunctionalInterface
public interface MinecraftMaterialEpochCompiler {
    MinecraftMaterialLookup compile(ResourcePackEpoch epoch, List<MinecraftMaterialRule> rules);
}
