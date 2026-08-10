package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.MaterialEmissionIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Analyzes Minecraft baked models into a host-neutral material emission index for one resource epoch. */
public final class MinecraftEmissionSemantics {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int VARIANT_PROBES = 8;

    private MinecraftEmissionSemantics() {
    }

    public static MaterialEmissionIndex analyze() {
        Map<ResourceId, Integer> materials = new HashMap<>();
        int states = 0;
        int failures = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int light = state.getLightEmission();
                if (light <= 0 || state.getRenderShape() != RenderShape.MODEL) continue;
                states++;
                try {
                    collectState(state, light, materials);
                } catch (Throwable throwable) {
                    failures++;
                    CausticaMod.LOGGER.debug("Could not analyze emissive material sprites for {}", state, throwable);
                }
            }
        }
        MaterialEmissionIndex result = new MaterialEmissionIndex(materials, states, failures);
        CausticaMod.LOGGER.info("RT emission semantics: emittingStates={}, materials={}, failedStates={}",
                states, result.maxEmission().size(), failures);
        return result;
    }

    private static void collectState(BlockState state, int light, Map<ResourceId, Integer> materials) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        if (model == null) return;
        for (int probe = 0; probe < VARIANT_PROBES; probe++) {
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(mixSeed(state.hashCode(), probe)), parts);
            for (BlockStateModelPart part : parts) {
                collectQuads(part.getQuads(null), light, materials);
                for (Direction direction : DIRECTIONS) collectQuads(part.getQuads(direction), light, materials);
            }
        }
    }

    private static void collectQuads(List<? extends BakedQuad> quads, int light,
                                     Map<ResourceId, Integer> materials) {
        for (var quad : quads) {
            var sprite = quad.materialInfo().sprite();
            if (sprite == null) continue;
            var id = sprite.contents().name();
            materials.merge(ResourceId.of(id.getNamespace(), id.getPath()), light, Math::max);
        }
    }

    private static long mixSeed(int stateHash, int probe) {
        long seed = Integer.toUnsignedLong(stateHash) ^ (0x9E3779B97F4A7C15L * (probe + 1L));
        seed ^= seed >>> 30;
        seed *= 0xBF58476D1CE4E5B9L;
        seed ^= seed >>> 27;
        return seed;
    }
}
