module nrd_signals;

import world_minimal;
import bindings;

public static const uint NRD_SIGNAL_RELAX = 0u;
public static const uint NRD_SIGNAL_REBLUR = 1u;
public static const float4 NRD_REBLUR_HIT_DISTANCE_PARAMETERS = float4(3.0, 0.1, 20.0, -25.0);
public static const float NRD_INVALID_VIEW_Z = 65504.0;

public float nrdLuminance(float3 value) {
    return dot(max(value, float3(0.0)), float3(0.2126, 0.7152, 0.0722));
}

float3 nrdLinearToYCoCg(float3 color) {
    return float3(dot(color, float3(0.25, 0.5, 0.25)),
            dot(color, float3(0.5, 0.0, -0.5)),
            dot(color, float3(-0.25, 0.5, -0.25)));
}

float3 nrdYCoCgToLinear(float3 color) {
    float t = color.x - color.z;
    return float3(t + color.y, color.x + color.z, t - color.y);
}

float nrdEncodeHitDistance(float hitDistance, float viewZ, float roughness) {
    if (worldBindings.nrdSignalEncoding == NRD_SIGNAL_RELAX) {
        return clamp(hitDistance, 0.0, 65504.0);
    }
    float normalization = (NRD_REBLUR_HIT_DISTANCE_PARAMETERS.x
            + abs(viewZ) * NRD_REBLUR_HIT_DISTANCE_PARAMETERS.y)
            * lerp(1.0, NRD_REBLUR_HIT_DISTANCE_PARAMETERS.z,
                    saturate(exp2(NRD_REBLUR_HIT_DISTANCE_PARAMETERS.w
                            * roughness * roughness)));
    return saturate(hitDistance / max(normalization, 1.0e-6));
}

float4 nrdPackRadianceHitDistance(float3 radiance, float hitDistance,
        float viewZ, float roughness) {
    radiance = clamp(max(radiance, float3(0.0)), float3(0.0), float3(65504.0));
    if (worldBindings.nrdSignalEncoding == NRD_SIGNAL_REBLUR) {
        radiance = nrdLinearToYCoCg(radiance);
    }
    return float4(radiance, nrdEncodeHitDistance(hitDistance, viewZ, roughness));
}

float3 nrdUnpackRadiance(float4 packed) {
    return worldBindings.nrdSignalEncoding == NRD_SIGNAL_REBLUR
            ? nrdYCoCgToLinear(packed.xyz) : packed.xyz;
}

public float3 nrdDemodulate(float3 radiance, float3 bsdfEstimate) {
    return radiance / max(bsdfEstimate, float3(1.0e-4));
}

public void nrdWritePrimarySignals(uint2 pixel, bool valid, float viewZ, float roughness,
        float3 diffuseRadiance, float3 specularRadiance, float3 residualRadiance,
        float diffuseHitDistance, float specularHitDistance,
        float3 diffuseBsdfEstimate, float3 specularBsdfEstimate) {
    float safeViewZ = valid ? max(abs(viewZ), 1.0e-4) : NRD_INVALID_VIEW_Z;
    float3 diffuse = valid ? nrdDemodulate(diffuseRadiance, diffuseBsdfEstimate) : float3(0.0);
    float3 specular = valid ? nrdDemodulate(specularRadiance, specularBsdfEstimate) : float3(0.0);
    worldFloat4StorageImage(worldBindings.resources.diffuseRadianceHitDistance)[pixel] =
            nrdPackRadianceHitDistance(diffuse, diffuseHitDistance, safeViewZ, 1.0);
    worldFloat4StorageImage(worldBindings.resources.specularRadianceHitDistance)[pixel] =
            nrdPackRadianceHitDistance(specular, specularHitDistance, safeViewZ, roughness);
    worldFloatStorageImage(worldBindings.resources.nrdViewZ)[pixel] = safeViewZ;
    worldFloat4StorageImage(worldBindings.resources.nrdStableRadiance)[pixel] =
            float4(clamp(residualRadiance, float3(0.0), float3(65504.0)), 1.0);
}

void nrdAddSignal(uint2 pixel, StorageImageIndex imageIndex, float3 radiance,
        float hitDistance, float viewZ, float roughness, bool nearestNonzeroHitDistance) {
    if (!any(radiance > 0.0)) return;
    RWTexture2D<float4> image = worldFloat4StorageImage(imageIndex);
    float4 previousPacked = image[pixel];
    float3 previous = nrdUnpackRadiance(previousPacked);
    float previousWeight = nrdLuminance(previous);
    float addedWeight = nrdLuminance(radiance);
    float previousHitDistance = previousPacked.w;
    if (worldBindings.nrdSignalEncoding == NRD_SIGNAL_REBLUR) {
        float normalization = (NRD_REBLUR_HIT_DISTANCE_PARAMETERS.x
                + abs(viewZ) * NRD_REBLUR_HIT_DISTANCE_PARAMETERS.y)
                * lerp(1.0, NRD_REBLUR_HIT_DISTANCE_PARAMETERS.z,
                        saturate(exp2(NRD_REBLUR_HIT_DISTANCE_PARAMETERS.w
                                * roughness * roughness)));
        previousHitDistance *= normalization;
    }
    float combinedHitDistance;
    if (nearestNonzeroHitDistance) {
        combinedHitDistance = previousHitDistance <= 0.0 ? hitDistance
                : hitDistance <= 0.0 ? previousHitDistance
                : min(previousHitDistance, hitDistance);
    } else {
        float directFraction = previousWeight / max(previousWeight + addedWeight, 1.0e-6);
        combinedHitDistance = lerp(hitDistance, previousHitDistance,
                min(directFraction, 0.5));
    }
    image[pixel] = nrdPackRadianceHitDistance(previous + radiance,
            combinedHitDistance, viewZ, roughness);
}

public void nrdAddIndirectSignals(uint2 pixel, float3 diffuseRadiance, float diffuseHitDistance,
        float3 specularRadiance, float specularHitDistance, float3 diffuseBsdfEstimate,
        float3 specularBsdfEstimate, float roughness) {
    float viewZ = worldFloatStorageImage(worldBindings.resources.nrdViewZ)[pixel];
    nrdAddSignal(pixel, worldBindings.resources.diffuseRadianceHitDistance,
            nrdDemodulate(diffuseRadiance, diffuseBsdfEstimate), diffuseHitDistance,
            viewZ, 1.0, false);
    nrdAddSignal(pixel, worldBindings.resources.specularRadianceHitDistance,
            nrdDemodulate(specularRadiance, specularBsdfEstimate), specularHitDistance,
            viewZ, roughness, true);
}
