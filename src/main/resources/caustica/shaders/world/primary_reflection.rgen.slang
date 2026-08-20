#define CAUSTICA_PRIMARY_REFLECTION 1
#include "primary_rgen.slang"

import caustica_api;
import caustica_surface;
import caustica_coverage;
import caustica_surface_modifier;
import caustica_types;
import caustica_builtin_sky;
import caustica_error_surface;
import caustica_error_coverage;

struct ReflectionSurfaces : ISurfaceDispatch {
    void evaluateSurface(uint implementation, SurfaceInput input, inout MaterialInput material) {
        ErrorSurface surface;
        surface.evaluateSurface(input, material);
    }

    float3 evaluateResponse(uint implementation, float3 radiance, SurfaceClosure closure) {
        ErrorSurface surface;
        return surface.evaluateResponse(radiance, closure);
    }

    MediumProperties evaluateMedium(uint implementation, MediumInput input) {
        ErrorSurface surface;
        return surface.evaluateMedium(input);
    }

    float3 evaluateMediumLighting(uint implementation, MediumLightingInput input) {
        ErrorSurface surface;
        return surface.evaluateMediumLighting(input);
    }
};

struct ReflectionSurfaceModifiers : ISurfaceModifierDispatch {
    void applySurfaceModifiers(SurfaceModifierInput input, inout MaterialInput material) {
    }
};

struct ReflectionCoverages : ICoverageDispatch {
    float4 evaluateCoverage(uint implementation, CoverageInput input) {
        ErrorCoverage coverage;
        return coverage.evaluateCoverage(input);
    }
};

struct ReflectionComposition : IComposition {
    typealias Sky = BuiltinSky;
    typealias Surfaces = ReflectionSurfaces;
    typealias Coverages = ReflectionCoverages;
    typealias SurfaceModifiers = ReflectionSurfaceModifiers;
};

[shader("raygeneration")]
void main() {
    runPrimary<ReflectionComposition>();
}
