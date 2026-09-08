package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;

import java.util.ArrayList;
import java.util.List;

/** Places section-local emitter descriptors in world coordinates for scene publication. */
public final class MinecraftTerrainLightAdapter {
    private MinecraftTerrainLightAdapter() { }

    public static MinecraftTerrainLightBatch describe(long sectionKey, long revision,
                                                      double originX, double originY, double originZ,
                                                      List<MinecraftTerrainEmitter> source) {
        var emitters = new ArrayList<MinecraftTerrainEmitter>(source.size());
        for (var emitter : source) {
            var light = emitter.descriptor();
            var descriptor = new LightDescriptor.Parallelogram(
                    light.positionX() + originX, light.positionY() + originY, light.positionZ() + originZ,
                    light.halfUx(), light.halfUy(), light.halfUz(),
                    light.halfVx(), light.halfVy(), light.halfVz(),
                    light.radianceRedCdM2(), light.radianceGreenCdM2(), light.radianceBlueCdM2());
            emitters.add(new MinecraftTerrainEmitter(descriptor, emitter.firstPrimitive(), emitter.primitiveCount()));
        }
        return new MinecraftTerrainLightBatch(sectionKey, revision, emitters);
    }
}
