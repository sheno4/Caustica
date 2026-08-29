module caustica_error_surface;

import caustica_types;
import caustica_surface;

/** Visible failure output for an invalid or rejected surface implementation. */
public struct ErrorSurface : ISurfaceModel {
    public override OpenPbrSurface evaluateSurface(SurfaceInput input) {
        OpenPbrSurface surface = {};
        surface.base_color = float3(1.0, 0.0, 1.0);
        surface.specular_roughness = 1.0;
        surface.specular_ior = 1.0;
        surface.emission_color = float3(1.0, 0.0, 1.0);
        surface.emission_luminance = 0.05;
        return surface;
    }
};
