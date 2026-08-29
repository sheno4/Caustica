module test_surface_modifier_broken;

import caustica_surface_modifier;
import caustica_types;

[[vk::binding(0, 3)]] Sampler2D brokenTexture;

public struct BrokenModifier : ISurfaceModifier {
    public void applySurfaceModifier(SurfaceModifierInput input, inout MaterialInput material) {
        material.baseColor *= brokenTexture.SampleLevel(float2(0.0), 0.0).rgb;
    }
};
