package dev.comfyfluffy.caustica.api.material;

import java.util.Objects;

/**
 * Everything the renderer itself reads about a material, which is very little: which registered surface
 * implementation shades the hit, whether the material bounds a participating medium, the coverage cutoff
 * any-hit compares against, and one word it never interprets.
 *
 * <p>No shading parameters. Colour, roughness, metalness, transmission and emission belong to the surface
 * implementation, which is the extension's own Slang.
 *
 * <p>{@code surface} is the id the program channel issued, named directly. Every cross-reference a submitted
 * record carries — a mesh's material, a placement's mesh and scene — is an issued identity, and this is no
 * exception.
 *
 * <p>{@code parameters} is 64 bits so it can simply be a device address — a material's parameters are
 * whatever buffer the source points it at, and there is no size beyond which an extension has to start
 * packing bits. It reaches the surface unchanged.
 */
public record MaterialDefinition(SurfaceId surface, MaterialTopology topology,
                                 float alphaCutoff, long parameters) {
    public MaterialDefinition {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(topology, "topology");
        if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException("alphaCutoff must be in [0,1]");
        }
    }
}
