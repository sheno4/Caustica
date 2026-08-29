module minecraft_custom_material;

import caustica_surface;
import caustica_coverage;
import caustica_types;

public struct CustomMinecraftSurface : ISurfaceModel {
    public override void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        uint checksum = input.providerData.words0.x ^ input.providerData.words0.y
                ^ input.providerData.words0.z ^ input.providerData.words0.w
                ^ input.providerData.words1.x ^ input.providerData.words1.y
                ^ input.providerData.words1.z ^ input.providerData.words1.w
                ^ input.providerData.words2.x ^ input.providerData.words2.y
                ^ input.providerData.words2.z ^ input.providerData.words2.w;
        material.baseColor *= 0.75 + float(checksum & 255u) / 1020.0;
    }
};

public struct CustomMinecraftCoverage : ICoverageModel {
    public override float4 evaluateCoverage(CoverageInput input) {
        float3 tint = input.baseColorFactor * input.tint * input.vertexColor.rgb;
        return float4(tint, input.vertexColor.a);
    }
};
