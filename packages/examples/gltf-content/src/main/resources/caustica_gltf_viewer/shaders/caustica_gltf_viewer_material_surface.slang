module caustica_gltf_viewer_material_surface;

import caustica_surface;
import caustica_types;

import caustica_gltf_viewer_primitive;

public struct GltfViewerMaterialSurface : ISurfaceModel {
    public override OpenPbrSurface evaluateSurface(SurfaceInput input) {
        GltfPrimitiveData primitive = ShaderDataPtr<GltfPrimitiveData>(input.bindingData)[input.primitiveIndex];
        OpenPbrSurface surface = {};
        surface.base_color = primitive.baseColor.rgb;
        surface.base_metalness = primitive.metallic;
        surface.specular_roughness = primitive.roughness;
        surface.specular_ior = 1.5;
        surface.emission_color = primitive.baseColor.rgb;
        surface.emission_luminance = primitive.emission;
        return surface;
    }
};
