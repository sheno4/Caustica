module caustica_builtin_surface;

import caustica_types;
import caustica_surface;
import caustica_medium;

/**
 * The reference surface: plain OpenPBR, and the fallback every material resolves to unless it names
 * another implementation. It edits nothing — the engine already decoded the description from the
 * material record — and stylizes nothing, so what it renders is what the parameters say.
 */
public struct BuiltinSurface : ISurfaceModel {
    public void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
    }

    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance;
    }

    public MediumProperties evaluateMedium(MediumInput input) {
        return referenceMedium(input);
    }

    public float3 evaluateMediumLighting(MediumLightingInput input) {
        return float3(1.0);
    }
};
