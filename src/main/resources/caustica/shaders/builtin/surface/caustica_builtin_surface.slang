module caustica_builtin_surface;

import caustica_surface;

/**
 * The reference surface: plain OpenPBR, and the fallback every material resolves to unless it names
 * another implementation. It edits nothing — the engine already decoded the description from the
 * material record — and stylizes nothing, so what it renders is what the parameters say.
 */
public struct BuiltinSurface : ISurfaceModel {};
