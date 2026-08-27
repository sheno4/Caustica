module caustica_api;

import caustica_surface;
import caustica_coverage;
import caustica_surface_modifier;
import caustica_environment;

public interface IComposition {
    // Scenes select among registered environments, so the composition binds the generated dispatch.
    associatedtype Environments : IEnvironmentDispatch;
    // Not a single surface model: materials name their own implementation, so what a composition binds is
    // the generated fan-out over every registered one.
    associatedtype Surfaces : ISurfaceDispatch;
    // Kept separate from Surfaces so traversal stages cannot reach shading code through the composition.
    associatedtype Coverages : ICoverageDispatch;
    associatedtype SurfaceModifiers : ISurfaceModifierDispatch;
};
