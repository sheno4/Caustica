module caustica_gltf_viewer_material_coverage;

import caustica_color;
import caustica_coverage;
import caustica_texture_resources;

/** glTF base-texture coverage and transmission tint for traversal shaders. */
public struct GltfViewerMaterialCoverage : ICoverageModel {
    public override float4 evaluateCoverage(CoverageInput input) {
        float3 geometryTint = input.tint * input.vertexColor.rgb * input.baseColorFactor;
        if ((input.flags & COVERAGE_TEXTURE_PRESENT) == 0u) {
            return float4(geometryTint, 1.0);
        }

        float4 texel = providerTextures[NonUniformResourceIndex(input.baseTextureIndex)]
                .SampleLevel(input.textureCoordinate, 0.0);
        float sampledCoverage = clamp(texel.a * input.vertexColor.a, 0.0, 1.0);
        float3 baseColor = (input.flags & COVERAGE_TEXTURE_LINEAR) != 0u
                ? texel.rgb : srgbToLinear(texel.rgb);
        float3 tint = lerp(float3(1.0), bt709ToAcesCg(baseColor) * geometryTint, sampledCoverage);
        float coverage = (input.flags & COVERAGE_MODE_OPAQUE) != 0u ? 1.0 : sampledCoverage;
        return float4(tint, coverage);
    }
};
