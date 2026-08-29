module showcase_surface;

import caustica_surface;
import caustica_resources;
import caustica_types;

namespace api_showcase {
public struct OpaqueSurface : ISurfaceModel {
    public override OpenPbrSurface evaluateSurface(SurfaceInput input) {
        OpenPbrSurface surface = {};
        surface.base_color = float3(0.8, 0.35, 0.1);
        surface.base_metalness = 0.7;
        surface.specular_roughness = 0.2;
        return surface;
    }
};

public struct TexturedSurface : ISurfaceModel {
    public override OpenPbrSurface evaluateSurface(SurfaceInput input) {
        OpenPbrSurface surface = {};
        surface.base_color = float3(0.2, 0.7, 0.25);
        surface.geometry_thin_walled = true;
        return surface;
    }
};
}
