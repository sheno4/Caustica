module caustica_builtin_coverage;

import caustica_color;
import caustica_coverage;
import caustica_texture_resources;
import bindings;
import world_common;

/** Coverage and transmission decode for the engine's current material binding. */
public struct BuiltinCoverage : ICoverageModel {
    public override float4 evaluateCoverage(CoverageInput input) {
        MaterialBinding binding = ConstPtr<MaterialBinding>(pc.materialTableAddr)[input.materialId];
        float3 geometryTint = input.tint * input.vertexColor.rgb;
        if (bindingCoverage(binding) == COVERAGE_OPAQUE) {
            return float4(bindingShadowTint(binding) * geometryTint, 1.0);
        }

        float4 texel = baseColorTextures[NonUniformResourceIndex(bindingBaseColorTextureIndex(binding))]
                .SampleLevel(input.textureCoordinate, 0.0);
        float coverage = clamp(texel.a * input.vertexColor.a, 0.0, 1.0);
        float3 baseColor = (bindingFlags(binding) & BINDING_BASE_COLOR_LINEAR) != 0u
                ? texel.rgb : srgbToLinear(texel.rgb);
        float3 tint = lerp(float3(1.0), bt709ToAcesCg(baseColor) * geometryTint, coverage);
        return float4(tint, coverage);
    }
};
