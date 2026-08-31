module stable_planes;

import bindings;
import world_common;
import world_minimal;
import stable_plane_types;

// Endpoint exploration follows deterministic delta branches while final guides remain dense.
public static const uint STABLE_PLANE_MAX_DELTA_SEGMENTS = 9u;

public DevicePtr<StablePlaneRecord> stablePlaneRecords() {
    return (DevicePtr<StablePlaneRecord>)worldBindings.stablePlaneBufferAddress;
}

public uint stablePlaneRecordIndex(uint2 pixel, uint2 extent, uint planeIndex) {
    return planeIndex * extent.x * extent.y + pixel.y * extent.x + pixel.x;
}

public void stablePlaneStoreRecord(uint2 pixel, uint2 extent, uint planeIndex,
        StablePlaneRecord record) {
    stablePlaneRecords()[stablePlaneRecordIndex(pixel, extent, planeIndex)] = record;
}

public StablePlaneRecord stablePlaneInvalidRecord() {
    StablePlaneRecord record = {};
    record.branchId = STABLE_PLANE_INVALID_BRANCH;
    return record;
}

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
};

public void stablePlaneWrite(uint2 pixel, StablePlaneSample sample) {
    worldFloat4StorageImage(worldBindings.resources.outputImage)[pixel] =
            float4(sample.radiance, 1.0);
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

public uint stablePlaneFirstEvent(StablePlaneRecord record) {
    uint depth = min((record.flags >> 8u) & 0xffu, 15u);
    return depth == 0u ? 0u : (record.branchId >> (2u * (depth - 1u))) & 3u;
}

public uint stablePlaneForEvent(uint2 pixel, uint2 extent, uint event) {
    DevicePtr<StablePlaneRecord> records = stablePlaneRecords();
    for (uint planeIndex = 1u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        StablePlaneRecord record = records[stablePlaneRecordIndex(pixel, extent, planeIndex)];
        if (record.branchId != STABLE_PLANE_INVALID_BRANCH
                && stablePlaneFirstEvent(record) == event) return planeIndex;
    }
    return 0u;
}

public void stablePlaneAccumulateSignals(uint2 pixel, uint2 extent, uint planeIndex,
        float3 diffuseRadiance, float diffuseHitDistance,
        float3 specularRadiance, float specularHitDistance) {
    DevicePtr<StablePlaneRecord> records = stablePlaneRecords();
    uint address = stablePlaneRecordIndex(pixel, extent, planeIndex);
    StablePlaneRecord record = records[address];
    record.noisyDiffuseRadiance += diffuseRadiance;
    record.noisySpecularRadiance += specularRadiance;
    if (diffuseHitDistance > 0.0) record.diffuseHitDistance = diffuseHitDistance;
    if (specularHitDistance > 0.0) {
        record.specularHitDistance = record.specularHitDistance > 0.0
                ? min(record.specularHitDistance, specularHitDistance) : specularHitDistance;
    }
    records[address] = record;
}

public void stablePlaneFinalizeGuides(uint2 pixel, uint2 extent, WorldPush frame) {
    DevicePtr<StablePlaneRecord> records = stablePlaneRecords();
    float weights[STABLE_PLANE_COUNT];
    float weightSum = 0.0;
    uint dominantIndex = 0u;
    for (uint planeIndex = 0u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        StablePlaneRecord record = records[stablePlaneRecordIndex(pixel, extent, planeIndex)];
        bool available = record.branchId != STABLE_PLANE_INVALID_BRANCH;
        weights[planeIndex] = available ? record.branchWeight * 0.2 + 0.01 : 0.0;
        if ((record.flags & STABLE_PLANE_FLAG_DOMINANT) != 0u) {
            dominantIndex = planeIndex;
            weights[planeIndex] += 0.05;
        }
        weightSum += weights[planeIndex];
    }

    float3 normal = float3(0.0, 0.0, 1.0e-6);
    float roughness = 0.0;
    float3 diffuseEstimate = float3(0.0);
    float3 specularEstimate = float3(0.0);
    for (uint planeIndex = 0u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        StablePlaneRecord record = records[stablePlaneRecordIndex(pixel, extent, planeIndex)];
        if (record.branchId == STABLE_PLANE_INVALID_BRANCH
                || (record.flags & STABLE_PLANE_FLAG_SKY) != 0u) continue;
        float weight = weights[planeIndex] / max(weightSum, 1.0e-6);
        normal += record.normal * weight;
        roughness += record.roughness * weight;
        diffuseEstimate += record.diffuseBsdfEstimate * weight;
        specularEstimate += record.specularBsdfEstimate * weight;
    }
    normal = normalize(normal);

    float3 stableRadiance = worldFloat4StorageImage(
            worldBindings.resources.nrdStableRadiance)[pixel].xyz * frame.preExposure;
    float stableMaximum = max(stableRadiance.x, max(stableRadiance.y, stableRadiance.z));
    float3 stableAlbedo = sqrt(stableRadiance / (float3(1.0) + stableMaximum));
    float stableAverage = dot(stableAlbedo, float3(1.0 / 3.0));
    float3 stableGreyMix = lerp(stableAlbedo, float3(0.5), 0.2);
    float mixWeight = stableAverage / (dot(diffuseEstimate, float3(1.0 / 3.0))
            + sqrt(stableAverage) + 1.0e-7);
    diffuseEstimate = lerp(diffuseEstimate, stableGreyMix, saturate(mixWeight));
    if (dot(diffuseEstimate + specularEstimate, float3(1.0 / 3.0)) < 0.05) {
        diffuseEstimate += float3(0.05);
    }

    StablePlaneRecord dominant = records[stablePlaneRecordIndex(pixel, extent, dominantIndex)];
    float4 currentClip = mul(frame.curViewProj,
            float4(dominant.currentVirtualPosition - frame.camOffset, 1.0));
    float4 previousClip = mul(frame.prevViewProj,
            float4(dominant.previousVirtualPosition - frame.camOffset + frame.camDelta, 1.0));
    float depth = (dominant.flags & STABLE_PLANE_FLAG_SKY) == 0u && currentClip.w > 0.0
            ? saturate(currentClip.z / currentClip.w) : 0.0;
    float2 motion = float2(0.0);
    if (currentClip.w > 0.0 && previousClip.w > 0.0) {
        motion = (previousClip.xy / previousClip.w - currentClip.xy / currentClip.w)
                * 0.5 * float2(extent);
    }
    float2 specularMotion = motion;
    StablePlaneRecord plane0 = records[stablePlaneRecordIndex(pixel, extent, 0u)];
    if (dominant.specularHitDistance > 0.001 && roughness < 0.25
            && (plane0.flags & STABLE_PLANE_FLAG_VALID) != 0u) {
        float3 primaryDirection = normalize(plane0.currentVirtualPosition - frame.camOffset);
        float3 imagePosition = plane0.currentVirtualPosition
                + primaryDirection * dominant.specularHitDistance;
        float4 specCurrentClip = mul(frame.curViewProj,
                float4(imagePosition - frame.camOffset, 1.0));
        float4 specPreviousClip = mul(frame.prevViewProj,
                float4(imagePosition - frame.camOffset + frame.camDelta, 1.0));
        if (specCurrentClip.w > 0.0 && specPreviousClip.w > 0.0) {
            specularMotion = (specPreviousClip.xy / specPreviousClip.w
                    - specCurrentClip.xy / specCurrentClip.w) * 0.5 * float2(extent);
        }
    }

    worldFloat4StorageImage(worldBindings.resources.normalGuide)[pixel] =
            float4(normal, roughness);
    worldFloat4StorageImage(worldBindings.resources.albedoGuide)[pixel] =
            float4(diffuseEstimate, 1.0);
    worldFloat4StorageImage(worldBindings.resources.specularAlbedoGuide)[pixel] =
            float4(specularEstimate, 1.0);
    worldFloatStorageImage(worldBindings.resources.depthGuide)[pixel] = depth;
    worldFloat2StorageImage(worldBindings.resources.motionGuide)[pixel] = motion;
    worldFloat2StorageImage(worldBindings.resources.specularMotionGuide)[pixel] = specularMotion;

    RWTexture2D<float4> output = worldFloat4StorageImage(worldBindings.resources.outputImage);
    float4 color = output[pixel];
    float maximum = max(color.x, max(color.y, color.z));
    float brightnessLimit = max(frame.preExposure * 4096.0, 1.0);
    if (maximum > brightnessLimit) color.xyz *= brightnessLimit / maximum;
    output[pixel] = color;
}
