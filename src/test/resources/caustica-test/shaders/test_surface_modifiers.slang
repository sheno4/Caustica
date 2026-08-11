module test_surface_modifiers;

import caustica_surface_modifier;
import caustica_types;

public struct FirstModifier : ISurfaceModifier {
    public void applySurfaceModifier(SurfaceModifierInput input, inout MaterialInput material) {
        material.baseColor *= 0.5;
    }
};

public struct SecondModifier : ISurfaceModifier {
    public void applySurfaceModifier(SurfaceModifierInput input, inout MaterialInput material) {
        material.baseColor += float3(0.1);
    }
};
