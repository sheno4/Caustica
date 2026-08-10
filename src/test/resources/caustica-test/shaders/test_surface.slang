module test_surface;

import caustica_types;
import caustica_surface;

// A surface implementation that edits the description it was handed and stylizes what comes back, which
// is the whole of what the narrowed interface allows.
public struct TestSurface : ISurfaceModel {
    public void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        material.baseColor = float3(1.0, 0.0, 0.0);
        material.geometryNormal = normalize(material.geometryNormal + float3(0.0, 0.1, 0.0));
        material.previousGeometryNormal = (input.surfaceFlags & SURFACE_RECONSTRUCTION_ANCHOR) != 0u
                ? input.geometricNormal : material.geometryNormal;
    }

    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance * 2.0;
    }
};
