module caustica_gltf_viewer_material_coverage;

import caustica_coverage;
import caustica_types;

import caustica_gltf_viewer_primitive;

public struct GltfViewerMaterialCoverage : ICoverageModel {
    public override float evaluateCoverage(CoverageInput input) {
        return ShaderDataPtr<GltfPrimitiveData>(input.bindingData)[input.primitiveIndex].baseColor.a;
    }
};
