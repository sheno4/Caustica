package dev.comfyfluffy.caustica.api.program;

/**
 * Synchronous declaration scope for one atomic program registration.
 *
 * <p>The builder is valid only during the callback passed to {@link ProgramChannel#register}. Do not retain
 * it or call it after that callback returns. Its methods issue typed, non-owning ids for the declaration's
 * export value; the enclosing registration remains the sole owner and removal authority.
 */
public interface ProgramBuilder {
    /**
     * Declare one surface implementation and its optional coverage implementation.
     * A geometry using cutout coverage may select the returned id only when the definition supplies coverage.
     */
    <B, N> SurfaceId<B, N> surface(SurfaceDefinition<B, N> definition);

    /** Declare one homogeneous interior-volume implementation. */
    <B, N> VolumeId<B, N> volume(VolumeDefinition<B, N> definition);

    /** Declare one environment implementation selected by a scene binding. */
    <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition);
}
