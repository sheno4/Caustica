module stable_planes;
import bindings;
import world_minimal;
import world_common;
import stable_plane_types;
import nrd_signals;

public static const uint STABLE_PLANE_MAX_DELTA_SEGMENTS = 9u;
public DevicePtr<StablePlaneRecord> stablePlaneRecords() {
    return (DevicePtr<StablePlaneRecord>)worldBindings.stablePlaneBufferAddress;
}
public uint stablePlaneRecordIndex(uint2 pixel, uint2 extent, uint plane) {
    return plane * extent.x * extent.y + pixel.y * extent.x + pixel.x;
}
public StablePlaneRecord stablePlaneInvalidRecord() {
    StablePlaneRecord record = {};
    record.branchId = STABLE_PLANE_INVALID_BRANCH;
    return record;
}
public void stablePlaneStoreRecord(uint2 pixel, uint2 extent, uint plane, StablePlaneRecord record) {
    stablePlaneRecords()[stablePlaneRecordIndex(pixel, extent, plane)] = record;
}

// The dominant endpoint owns screen depth/motion; NRD consumes each plane's own endpoint separately.
public void stablePlaneExport(uint2 pixel, uint2 extent, WorldPush frame, bool finalSignals) {
    uint dominant = 0u, available = 0u;
    float largest = -1.0;
    float3 diffuse = float3(0.0), specular = float3(0.0);
    float3 diffuseEstimate = float3(0.0), specularEstimate = float3(0.0);
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        StablePlaneRecord record = stablePlaneRecords()[stablePlaneRecordIndex(pixel, extent, i)];
        if (record.branchId == STABLE_PLANE_INVALID_BRANCH) continue;
        available++;
        if (record.throughputLuminance > largest) {
            largest = record.throughputLuminance;
            dominant = i;
        }
        diffuse += record.noisyDiffuseRadiance;
        specular += record.noisySpecularRadiance;
        diffuseEstimate += record.diffuseBsdfEstimate;
        specularEstimate += record.specularBsdfEstimate;
    }
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        uint address = stablePlaneRecordIndex(pixel, extent, i);
        StablePlaneRecord record = stablePlaneRecords()[address];
        record.flags &= ~STABLE_PLANE_FLAG_DOMINANT;
        if (i == dominant) record.flags |= STABLE_PLANE_FLAG_DOMINANT;
        stablePlaneRecords()[address] = record;
    }
    StablePlaneRecord record = stablePlaneRecords()[stablePlaneRecordIndex(pixel, extent, dominant)];
    bool valid = (record.flags & STABLE_PLANE_FLAG_VALID) != 0u;
    float4 current = mul(frame.curViewProj, float4(record.currentVirtualPosition - frame.camOffset, 1.0));
    float4 previous = mul(frame.prevViewProj,
            float4(record.previousVirtualPosition - frame.camOffset + frame.camDelta, 1.0));
    float2 motion = current.w > 0.0 && previous.w > 0.0
            ? (previous.xy / previous.w - current.xy / current.w) * 0.5 * float2(extent) : float2(0.0);
    worldFloatStorageImage(worldBindings.resources.depthGuide)[pixel] =
            valid && current.w > 0.0 ? saturate(current.z / current.w) : 0.0;
    worldFloatStorageImage(worldBindings.resources.nrdViewZ)[pixel] =
            valid ? max(abs(current.w), 1.0e-4) : NRD_INVALID_VIEW_Z;
    worldFloat2StorageImage(worldBindings.resources.motionGuide)[pixel] = motion;
    worldFloat2StorageImage(worldBindings.resources.specularMotionGuide)[pixel] = motion;
    worldFloat4StorageImage(worldBindings.resources.normalGuide)[pixel] =
            float4(record.normal, record.roughness);
    worldFloat4StorageImage(worldBindings.resources.albedoGuide)[pixel] =
            float4(diffuseEstimate, 0.0);
    worldFloat4StorageImage(worldBindings.resources.specularAlbedoGuide)[pixel] =
            float4(specularEstimate, 1.0);
    worldFloat4StorageImage(worldBindings.resources.stablePlaneMetadataImage)[pixel] =
            float4(float(available) / 3.0, float(dominant) * 0.5,
                    float((record.flags >> 8u) & 255u) / 9.0,
                    (record.flags & STABLE_PLANE_FLAG_TRANSMISSION) != 0u ? 1.0 : 0.0);
    if (finalSignals) {
        float3 stable = worldFloat4StorageImage(worldBindings.resources.nrdStableRadiance)[pixel].xyz;
        worldFloat4StorageImage(worldBindings.resources.outputImage)[pixel] =
                float4(clamp((stable + diffuse + specular) * frame.preExposure, 0.0, 65504.0), 1.0);
        nrdWritePrimarySignals(pixel, valid, current.w, record.roughness,
                diffuse, specular, stable, record.diffuseHitDistance, record.specularHitDistance,
                diffuseEstimate, specularEstimate);
    }
}
