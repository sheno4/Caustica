module caustica_gltf_viewer_material_coverage;

import caustica_coverage;
import caustica_types;

struct GltfPrimitiveData {
    float4 baseColor;
    float roughness;
    float metallic;
    float emission;
    float reserved;
};

public struct GltfViewerMaterialCoverage : ICoverageModel {
    public override float evaluateCoverage(CoverageInput input) {
        return ShaderDataPtr<GltfPrimitiveData>(input.bindingData)[input.primitiveIndex].baseColor.a;
    }
};
