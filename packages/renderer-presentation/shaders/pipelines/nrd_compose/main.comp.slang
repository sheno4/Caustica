// NRD filters demodulated illumination. Reapply the BSDF estimates to their matching lobes before
// combining them, then enter the renderer's pre-exposed scene-linear storage domain exactly once.
import display_common;

[[vk::push_constant]] NrdComposePush pc;

float3 nrdRadiance(float3 encoded) {
    if (pc.signalEncoding == 0u) return max(encoded, float3(0.0));
    float t = encoded.x - encoded.z;
    return max(float3(t + encoded.y, encoded.x + encoded.z, t - encoded.y), float3(0.0));
}

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    [format("rgba16f")] RWTexture2D<float4> outputImage =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.outputImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> denoisedDiffuse =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.denoisedDiffuseImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> denoisedSpecular =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.denoisedSpecularImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> stableRadiance =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.stableRadianceImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> diffuseAlbedo =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.diffuseAlbedoImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> specularAlbedo =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.specularAlbedoImageIndex)];

    uint width, height;
    outputImage.GetDimensions(width, height);
    if (tid.x >= width || tid.y >= height) return;

    int2 pixel = int2(tid.xy);
    float3 diffuseBsdf = max(diffuseAlbedo[pixel].rgb, float3(1.0e-4));
    float3 specularBsdf = max(specularAlbedo[pixel].rgb, float3(1.0e-4));
    // Alpha carries the method-specific hit distance and must not participate in reconstruction.
    float3 diffuse = nrdRadiance(denoisedDiffuse[pixel].rgb) * diffuseBsdf;
    float3 specular = nrdRadiance(denoisedSpecular[pixel].rgb) * specularBsdf;
    float3 stable = max(stableRadiance[pixel].rgb, float3(0.0));
    outputImage[pixel] = float4(clamp((stable + diffuse + specular) * pc.preExposure,
            float3(0.0), float3(65504.0)), 1.0);
}
