module caustica_minecraft_coverage;

import caustica_coverage;
import caustica_minecraft_material;

/** Traversal-safe Minecraft base-texture coverage. */
public struct MinecraftCoverage : ICoverageModel {
    public float evaluateCoverage(CoverageInput input) {
        MinecraftHitData hit = minecraftHitData(input.implementationData, input.bindingData,
                input.instanceData, input.primitiveIndex, input.barycentrics);
        if ((hit.primitive.textureFlags & MINECRAFT_TEXTURE_PRESENT) == 0u) {
            return clamp(hit.vertexColor.a, 0.0, 1.0);
        }
        return clamp(minecraftSampleBase(hit, hit.textureCoordinate, 0.0).a
                * hit.vertexColor.a, 0.0, 1.0);
    }
};
