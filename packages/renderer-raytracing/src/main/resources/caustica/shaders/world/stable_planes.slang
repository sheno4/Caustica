module stable_planes;

import bindings;
import world_minimal;

// Primary-surface reconstruction guides remain aligned with dense depth and motion. A short,
// deterministic delta chain additionally unfolds reflected geometry for the specular-motion guide.
public static const uint STABLE_PLANE_MAX_DELTA_SEGMENTS = 3u;

public struct StablePlaneSample {
    public float3 radiance;
    public float3 normal;
    public float roughness;
    public float3 diffuseAlbedo;
    public float3 specularAlbedo;
    public float primaryDepth;
    public float2 primaryMotion;
    public float2 specularMotion;
    public bool emissive;
    public bool valid;
    public uint deltaChainDepth;
    public uint reflectionCount;
    public bool containsTransmission;
};

public struct StablePlaneReflection {
    public float3 currentPosition;
    public float3 previousPosition;
    public float3 currentNormal;
    public float3 previousNormal;
};

public float3 stablePlaneReflectPoint(float3 point, float3 planePosition, float3 planeNormal) {
    return point - 2.0 * dot(point - planePosition, planeNormal) * planeNormal;
}

public StablePlaneReflection stablePlaneReflectionAt(
        StablePlaneReflection reflection0, StablePlaneReflection reflection1,
        StablePlaneReflection reflection2, uint index) {
    return index == 0u ? reflection0 : index == 1u ? reflection1 : reflection2;
}

public float3 stablePlaneUnfoldPoint(float3 point, bool previous,
        StablePlaneReflection reflection0, StablePlaneReflection reflection1,
        StablePlaneReflection reflection2, uint reflectionCount) {
    for (uint reverseIndex = reflectionCount; reverseIndex > 0u; reverseIndex--) {
        StablePlaneReflection plane = stablePlaneReflectionAt(
                reflection0, reflection1, reflection2, reverseIndex - 1u);
        point = previous
                ? stablePlaneReflectPoint(point, plane.previousPosition, plane.previousNormal)
                : stablePlaneReflectPoint(point, plane.currentPosition, plane.currentNormal);
    }
    return point;
}

public void stablePlaneWrite(uint2 pixel, StablePlaneSample sample) {
    float chainScale = 1.0 / float(STABLE_PLANE_MAX_DELTA_SEGMENTS);
    worldFloat4StorageImage(worldBindings.resources.outputImage)[pixel] =
            float4(sample.radiance, 1.0);
    // x: valid primary surface, y: delta-chain depth, z: reflected interface count,
    // w: the deterministic chain crossed at least one refractive interface.
    worldFloat4StorageImage(worldBindings.resources.stablePlaneMetadataImage)[pixel] = float4(
            sample.valid ? 1.0 : 0.0,
            float(min(sample.deltaChainDepth, STABLE_PLANE_MAX_DELTA_SEGMENTS)) * chainScale,
            float(min(sample.reflectionCount, STABLE_PLANE_MAX_DELTA_SEGMENTS)) * chainScale,
            sample.containsTransmission ? 1.0 : 0.0);
    worldFloat4StorageImage(worldBindings.resources.normalGuide)[pixel] =
            float4(sample.normal, sample.roughness);
    worldFloat4StorageImage(worldBindings.resources.albedoGuide)[pixel] =
            float4(sample.diffuseAlbedo, sample.emissive ? 1.0 : 0.0);
    worldFloatStorageImage(worldBindings.resources.depthGuide)[pixel] = sample.primaryDepth;
    worldFloat2StorageImage(worldBindings.resources.motionGuide)[pixel] = sample.primaryMotion;
    worldFloat4StorageImage(worldBindings.resources.specularAlbedoGuide)[pixel] =
            float4(sample.specularAlbedo, 1.0);
    worldFloat2StorageImage(worldBindings.resources.specularMotionGuide)[pixel] =
            sample.specularMotion;
}

public void stablePlaneAddRadiance(uint2 pixel, float3 radiance) {
    RWTexture2D<float4> stable = worldFloat4StorageImage(worldBindings.resources.outputImage);
    float4 stableValue = stable[pixel];
    stable[pixel] = float4(clamp(stableValue.xyz + radiance, float3(0.0), float3(65504.0)),
            stableValue.w);
}
