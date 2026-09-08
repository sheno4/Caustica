module caustica_gltf_viewer_primitive;

// Packed as eight floats by GltfMeshUploader, with one record per triangle.
public struct GltfPrimitiveData {
    public float4 baseColor;
    public float roughness;
    public float metallic;
    public float emission;
    public float reserved;
};
