module caustica_api;

import caustica_surface;
import caustica_coverage;
import caustica_volume;
import caustica_environment;

public interface IComposition {
    // Scenes select among registered environments, so the composition binds the generated dispatch.
    associatedtype Environments : IEnvironmentDispatch;
    // Mesh geometries select registered implementations, so the composition binds their generated fan-out.
    associatedtype Surfaces : ISurfaceDispatch;
    // Kept separate from Surfaces so traversal stages cannot reach shading code through the composition.
    associatedtype Coverages : ICoverageDispatch;
    // Geometry independently selects the interior implementation used at a volume boundary.
    associatedtype Volumes : IVolumeDispatch;
};
