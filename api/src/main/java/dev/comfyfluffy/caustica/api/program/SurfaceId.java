package dev.comfyfluffy.caustica.api.program;

/**
 * A registered surface and coverage implementation selected directly by a mesh geometry. The id belongs
 * to the context which issued it; geometry in that context may retain it, while a stale id resolves to the
 * renderer's visible error surface. The identity is opaque and meaningful only to its issuing context.
 *
 * @param <B> required geometry-slot binding data schema
 * @param <N> required mesh-placement instance data schema
 */
public interface SurfaceId<B, N> {
}
