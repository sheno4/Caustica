module caustica_surface;

import caustica_types;

/**
 * A surface implementation supplies PARAMETERS, not lobes. The engine implements the OpenPBR BSDF once,
 * so the value/pdf agreement every lighting backend targets cannot be violated by an implementation
 * rather than merely being required of it.
 *
 * The cost of that narrowing is deliberate: a genuinely different lobe (hair, cloth, a coat) is engine
 * work, and OpenPBR already reserves the parameters for those. `evaluateResponse` remains the look hook.
 */
public interface ISurfaceModel {
    /**
     * Produce the OpenPBR description for this hit from the input's surface, geometry, and instance data
     * words, the extension's own textures, and its own procedural evaluation. Declare an
     * `OpenPbrSurface` and write only what varies; every field already holds the specification's default.
     */
    public OpenPbrSurface evaluateSurface(SurfaceInput input) { return {}; }

    /** Applied once to a vertex's total shaded contribution. The reference look is identity. */
    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance;
    }

};

/**
 * The generated fan-out over every registered surface implementation, selected per mesh geometry rather than
 * per composition. The engine names only this interface; the switch behind it is generated with the
 * composition root, so adding an implementation costs a case and no engine change.
 */
public interface ISurfaceDispatch {
    public OpenPbrSurface evaluateSurface(uint implementation, SurfaceInput input);
    public float3 evaluateResponse(uint implementation, float3 radiance, SurfaceClosure surface);
};
