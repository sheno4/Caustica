module test_surface_broken;

import caustica_types;
import caustica_surface;

// Deliberately broken the way a third-party implementation is most likely to be: Slang forbids the type
// satisfying a generic interface parameter from declaring its own descriptor-bound globals, and the
// diagnostic only appears when it is specialized into a real stage. Compiling this module on its own
// succeeds, which is exactly why the engine's validation specializes instead.
[[vk::binding(0, 3)]] Sampler2D brokenTexture;

public struct BrokenSurface : ISurfaceModel {
    public void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        material.baseColor = brokenTexture.SampleLevel(input.textureCoordinate, 0.0).rgb;
    }

    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance;
    }
};
