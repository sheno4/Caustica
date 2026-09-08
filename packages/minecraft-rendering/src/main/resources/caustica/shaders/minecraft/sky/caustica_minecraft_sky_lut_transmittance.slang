// Transmittance LUT bake (Hillaire 2020, §5.1 / Bruneton 2008). For each (altitude, cos zenith) texel,
// integrate the optical depth from that point to the top of the atmosphere and store exp(-depth).
//
// The medium is fixed between atmosphere LUT rebuilds. Marching below the horizon includes the full
// chord through dense sea-level air, yielding zero transmittance without a separate ground test.
module caustica_minecraft_sky_lut_transmittance;

import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_sky_lut_types;

[[vk::push_constant]] SkyLutPush pc;

public void runSkyTransmittance(uint3 tid) {
    RWTexture2D<float4> transmittanceImage =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.destinationImageIndex)];
    if (tid.x >= uint(TRANSMITTANCE_LUT_W) || tid.y >= uint(TRANSMITTANCE_LUT_H)) {
        return;
    }
    // The parameterisation is defined on the closed [0,1] range, so the outermost texels must land on 0
    // and 1 exactly — a plain (i+0.5)/N would shrink the LUT's domain and bias every sample taken from it.
    float2 uv = float2(float(tid.x), float(tid.y))
              / float2(float(TRANSMITTANCE_LUT_W - 1), float(TRANSMITTANCE_LUT_H - 1));
    float radiusKm;
    float cosZenith;
    transmittanceLutParams(uv, radiusKm, cosZenith);

    float3 origin = float3(0.0, radiusKm, 0.0);
    float3 dir = float3(sqrt(max(1.0 - cosZenith * cosZenith, 0.0)), cosZenith, 0.0);
    float tMax = raySphereFarthest(origin, dir, ATMOS_TOP_KM);

    float3 opticalDepth = float3(0.0);
    if (tMax > 0.0) {
        float dt = tMax / float(TRANSMITTANCE_STEPS);
        for (int i = 0; i < TRANSMITTANCE_STEPS; i++) {
            float3 p = origin + dir * ((float(i) + 0.5) * dt);
            MediumSample medium = sampleMedium(length(p) - ATMOS_BOTTOM_KM);
            opticalDepth += medium.extinction * dt;
        }
    }
    transmittanceImage[tid.xy] = float4(exp(-opticalDepth), 1.0);
}
