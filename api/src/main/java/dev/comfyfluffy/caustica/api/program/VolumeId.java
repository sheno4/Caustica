package dev.comfyfluffy.caustica.api.program;

/**
 * A registered volume implementation selected by a mesh geometry's interior slot. The id belongs to the
 * context which issued it; geometry in that context may retain it, while a stale id resolves to vacuum.
 * The identity is opaque and meaningful only to its issuing context.
 *
 * @param <B> required geometry-slot binding data schema
 * @param <N> required mesh-placement instance data schema
 */
public interface VolumeId<B, N> {
}
