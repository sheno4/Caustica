module caustica_surface_modifier;

import caustica_types;

/** A projected edit applied only to geometry carrying the modifier-receiver semantic. */
public interface ISurfaceModifier {
    public void applySurfaceModifier(SurfaceModifierInput input, inout MaterialInput material);
};

/** Generated sequential fan-out over every registered modifier. */
public interface ISurfaceModifierDispatch {
    public void applySurfaceModifiers(SurfaceModifierInput input, inout MaterialInput material);
};
