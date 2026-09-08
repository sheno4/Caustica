module caustica_surface;

import caustica_types;

/**
 * Supplies the supported OpenPBR parameters. The engine evaluates and samples the BSDF, keeping its
 * scattering value and probability density consistent. Additional lobes require engine support and
 * corresponding parameters in OpenPbrSurface.
 */
public interface ISurfaceModel {
    /**
     * Produce the OpenPBR description for this hit from the input's implementation, binding, and instance data
     * words, the extension's own textures, and its own procedural evaluation. Value-initialize an
     * `OpenPbrSurface` with `= {}`, then write only what varies.
     */
    public OpenPbrSurface evaluateSurface(SurfaceInput input) { return {}; }

};

/**
 * The generated fan-out over every registered surface implementation, selected per mesh geometry rather than
 * per composition. The engine names only this interface; the switch behind it is generated with the
 * composition root, so adding an implementation costs a case and no engine change.
 */
public interface ISurfaceDispatch {
    public OpenPbrSurface evaluateSurface(uint implementation, SurfaceInput input);
};
