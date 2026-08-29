module caustica_gltf_viewer_portal_surface;

import caustica_surface;
import caustica_types;

float viewerPortalHash(float2 cell) {
    float3 p = frac(float3(cell.x, cell.y, cell.x) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return frac((p.x + p.y) * p.z);
}

public struct GltfViewerPortalSurface : ISurfaceModel {
    public override OpenPbrSurface evaluateSurface(SurfaceInput input) {
        float3 position = input.proceduralPosition;
        float2 facePosition = abs(input.geometricNormal.y) > 0.8 ? position.xz
                : abs(input.geometricNormal.x) > 0.8 ? position.zy : position.xy;
        float2 field = facePosition * 2.2;
        float2 cell = floor(field);
        float2 local = frac(field) - 0.5;
        float seed = viewerPortalHash(cell);
        float stars = pow(saturate(1.0 - 2.0 * length(local)), 18.0) * smoothstep(0.91, 1.0, seed);
        float flow = 0.5 + 0.5 * sin(facePosition.x * 1.7 + facePosition.y * 1.2
                + input.time * 0.55 + seed * 6.2831853);
        float3 deep = float3(0.008, 0.0015, 0.026);
        float3 violet = float3(0.32, 0.025, 0.78);
        float3 starlight = float3(0.48, 0.75, 1.0);
        OpenPbrSurface surface = {};
        surface.base_color = deep;
        surface.specular_roughness = 0.38;
        surface.specular_ior = 1.35;
        surface.emission_color = lerp(deep, violet, flow) + starlight * stars;
        surface.emission_luminance = 90.0 + 900.0 * stars;
        return surface;
    }
};
