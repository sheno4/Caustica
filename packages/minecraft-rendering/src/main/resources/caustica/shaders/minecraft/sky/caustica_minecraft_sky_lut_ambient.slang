module caustica_minecraft_sky_lut_ambient;

import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_sky_lut_types;
import caustica_resources;

[[vk::push_constant]] SkyLutPush pc;

/** Mean incident radiance over solid angle; the diffuse sky includes both celestial scattering slices. */
public void runSkyAmbient() {
    SkyState sky = skyState(pc.inputs);
    float3 sum = 0.0;
    for (uint polar = 0; polar < 8; polar++) {
        float y = 1.0 - 2.0 * (float(polar) + 0.5) / 8.0;
        float radial = sqrt(max(1.0 - y * y, 0.0));
        for (uint azimuth = 0; azimuth < 8; azimuth++) {
            float angle = 2.0 * SKY_PI * (float(azimuth) + 0.5) / 8.0;
            float3 dir = float3(cos(angle) * radial, y, sin(angle) * radial);
            float2 sunUv = skyViewLutUv(sky.viewerRadiusKm, y,
                    lightViewCosine(dir, sky.sunDir), SKY_VIEW_BODY_SUN);
            float2 moonUv = skyViewLutUv(sky.viewerRadiusKm, y,
                    lightViewCosine(dir, sky.moonDir), SKY_VIEW_BODY_MOON);
            sum += sampleTexture2DLod(pc.multiScatterImage, pc.sampler, sunUv, 0.0).rgb
                    + sampleTexture2DLod(pc.multiScatterImage, pc.sampler, moonUv, 0.0).rgb;
        }
    }
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationImageIndex];
    output[uint2(0)] = float4(sum / 64.0 + sky.airglowLuminance, 1.0);
}
