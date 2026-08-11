module caustica_api;

import caustica_surface;
import caustica_surface_modifier;
import caustica_sky;

public interface IComposition {
    associatedtype Sky : ISkyModel;
    // Not a single surface model: materials name their own implementation, so what a composition binds is
    // the generated fan-out over every registered one.
    associatedtype Surfaces : ISurfaceDispatch;
    associatedtype SurfaceModifiers : ISurfaceModifierDispatch;
};
