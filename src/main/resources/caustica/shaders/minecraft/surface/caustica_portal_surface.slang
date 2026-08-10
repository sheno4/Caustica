module caustica_portal_surface;

import caustica_surface;
import caustica_types;

float portalHash(float2 cell) {
    float3 p = frac(float3(cell.x, cell.y, cell.x) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return frac((p.x + p.y) * p.z);
}

/** Procedural, world-pinned emissive surface used by Minecraft's end-portal material adapter. */
public struct PortalSurface : ISurfaceModel {
    public void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        float2 world = input.position.xz + input.proceduralDomainAnchor;
        float2 field = world * 0.45;
        float2 cell = floor(field);
        float2 local = frac(field) - 0.5;

        float starSeed = portalHash(cell);
        float starShape = pow(saturate(1.0 - 2.0 * length(local)), 18.0);
        float stars = starShape * smoothstep(0.94, 1.0, starSeed);
        float flow = 0.5 + 0.5 * sin(world.x * 0.31 + sin(world.y * 0.23)
                + input.time * 0.35 + starSeed * 6.2831853);

        float3 deep = float3(0.035, 0.008, 0.09);
        float3 violet = float3(0.34, 0.055, 0.72);
        float3 starlight = float3(0.55, 0.78, 1.0);
        material.baseColor = float3(0.002, 0.001, 0.006);
        material.baseMetalness = 0.0;
        material.specularRoughness = 0.42;
        material.specularIor = 1.35;
        material.specularColor = float3(1.0);
        material.transmissionWeight = 0.0;
        material.emissionColor = lerp(deep, violet, flow) + starlight * stars;
        material.emissionLuminance = 24.0 + 160.0 * stars;
        material.geometryOpacity = 1.0;
        material.geometryThinWalled = true;
    }

    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance;
    }
};
