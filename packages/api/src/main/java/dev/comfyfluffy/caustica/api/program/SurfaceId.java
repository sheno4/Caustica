package dev.comfyfluffy.caustica.api.program;

/**
 * A registered surface and coverage implementation selected directly by mesh geometry. The id is a
 * same-session, non-owning reference which may be explicitly handed to another contribution. Its
 * {@link ProgramRegistration} retains removal authority, and copying or retaining the id does not extend
 * that registration's lifetime. A stale id resolves to the renderer's visible error surface.
 *
 * @param <B> required geometry-slot binding data schema
 * @param <N> required mesh-placement instance data schema
 */
public interface SurfaceId<B, N> {
}
