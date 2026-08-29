package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;

/** Translates Minecraft block and texture semantics into renderer OpenPBR compile inputs. */
public final class MinecraftMaterialClassifier {
    public static final float ICE_IOR = 1.309f;
    public static final float WATER_IOR = 1.333f;

    private static final Map<String, Float> IOR_BY_MATERIAL = Map.of(
            "block/ice", ICE_IOR,
            "block/packed_ice", ICE_IOR,
            "block/blue_ice", ICE_IOR,
            "block/frosted_ice_0", ICE_IOR,
            "block/frosted_ice_1", ICE_IOR,
            "block/frosted_ice_2", ICE_IOR,
            "block/frosted_ice_3", ICE_IOR);

    private static final Set<Block> POLISHED = Set.of(
            Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
            Blocks.SMOOTH_STONE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

    private MinecraftMaterialClassifier() {
    }

    public static MinecraftMaterialClassification classify(BlockState state) {
        if (state == null) {
            return new MinecraftMaterialClassification(null, MinecraftMaterialProfile.ROUGH_DIELECTRIC);
        }
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new MinecraftMaterialClassification(ResourceId.of(id.getNamespace(), id.getPath()), profile(state));
    }

    public static MinecraftMaterialProfile profile(BlockState state) {
        if (state == null) return MinecraftMaterialProfile.ROUGH_DIELECTRIC;
        SoundType sound = state.getSoundType();
        if (isMetal(sound)) return MinecraftMaterialProfile.CONDUCTOR;
        if (sound == SoundType.GLASS) return MinecraftMaterialProfile.SMOOTH_DIELECTRIC;
        if (POLISHED.contains(state.getBlock())) return MinecraftMaterialProfile.POLISHED_DIELECTRIC;
        return MinecraftMaterialProfile.ROUGH_DIELECTRIC;
    }

    public static float dielectricIor(ResourceId material) {
        if (material == null) return OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR;
        return IOR_BY_MATERIAL.getOrDefault(material.path(),
                OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR);
    }

    private static boolean isMetal(SoundType sound) {
        return sound == SoundType.METAL || sound == SoundType.COPPER
                || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.ANVIL
                || sound == SoundType.CHAIN;
    }
}
