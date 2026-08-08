module caustica_api;

import caustica_surface;
import caustica_sky;

public interface IComposition {
    associatedtype Sky : ISkyModel;
    associatedtype Surface : ISurfaceModel;
};
