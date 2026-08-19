module caustica_error_surface;

import caustica_types;
import caustica_surface;

/** Visible failure output for an invalid or rejected surface implementation. */
public struct ErrorSurface : ISurfaceModel {
    public override void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        material.geometryNormal = input.geometricNormal;
        material.previousGeometryNormal = input.geometricNormal;
        material.baseColor = float3(1.0, 0.0, 1.0);
        material.baseMetalness = 0.0;
        material.specularRoughness = 1.0;
        material.specularIor = 1.0;
        material.transmissionWeight = 0.0;
        material.transmissionColor = float3(0.0);
        material.subsurfaceWeight = 0.0;
        material.subsurfaceColor = float3(0.0);
        material.subsurfaceScatterAnisotropy = 0.0;
        material.emissionColor = float3(1.0, 0.0, 1.0);
        material.emissionLuminance = 0.05;
    }
};
