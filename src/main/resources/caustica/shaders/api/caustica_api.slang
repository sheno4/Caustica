module caustica_api;

import caustica_surface;
import caustica_sky;
import caustica_medium;

public interface IComposition {
    associatedtype Sky : ISkyModel;
    associatedtype Surface : ISurfaceModel;
    associatedtype Medium : IMediumModel;
};
