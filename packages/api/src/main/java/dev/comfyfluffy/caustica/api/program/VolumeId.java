package dev.comfyfluffy.caustica.api.program;

/**
 * A registered volume implementation selected by a mesh geometry's interior slot. The id is a same-session,
 * non-owning reference which may be explicitly handed to another contribution. Its
 * {@link ProgramRegistration} retains removal authority, and copying or retaining the id does not extend
 * that registration's lifetime. A stale id resolves to vacuum.
 *
 * @param <B> required geometry-slot binding data schema
 * @param <N> required mesh-placement instance data schema
 */
public interface VolumeId<B, N> {
}
