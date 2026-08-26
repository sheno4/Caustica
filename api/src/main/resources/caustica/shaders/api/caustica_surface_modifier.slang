module caustica_surface_modifier;

import caustica_types;

/** A projected edit applied only to geometry carrying the modifier-receiver semantic. */
public interface ISurfaceModifier {
    public OpenPbrSurface applySurfaceModifier(SurfaceModifierInput input, OpenPbrSurface material);
};

/** Generated sequential fan-out over every registered modifier. */
public interface ISurfaceModifierDispatch {
    public OpenPbrSurface applySurfaceModifiers(SurfaceModifierInput input, OpenPbrSurface material);
};
