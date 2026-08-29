package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneHandle;

/**
 * Everything an extension compiles into one render session's world ray-tracing program.
 *
 * <p>An implementation is not declared once at startup — it is added and dropped at any time, which is what
 * makes switching a feature off mean <em>not present</em> rather than present-but-disabled. There is no gate
 * to consult and no way to name something that is not there.
 *
 * <h2>Ids are synchronous; compilation is observable without blocking</h2>
 *
 * A returned {@link ProgramUpdate} contains an id usable immediately and a {@link ProgramTicket} for the
 * composition that implements it. Visibility depends on the implementation kind:
 *
 * <ul>
 * <li>A surface or volume becomes selected when mesh geometry naming it is published. An error-free reload
 * waits for readiness and publishes the affected mesh changes atomically.</li>
 * <li>An environment becomes selected when a {@link SceneHandle} receives an {@link EnvironmentBinding}
 * naming it. Wait for readiness before that replacement when an error environment is unacceptable.</li>
 * </ul>
 *
 * <p>There is no general program batch. Surface, volume, and environment switching already occurs at the
 * external publication boundary that names them.
 *
 * <h2>Recompiles are coalesced by the renderer</h2>
 *
 * Adding or dropping recompiles the world program, which is expensive, but that is not the caller's problem
 * to schedule. The renderer rebuilds at most once per boundary, so a run of additions costs one rebuild —
 * and it coalesces across extensions, which a caller-side batch could never do.
 *
 * <p>Each ticket identifies the exact requested composition after its accepted operation. A later accepted
 * operation may coalesce the work and make that exact intermediate composition unnecessary; its ticket then
 * becomes {@link ProgramTicket.State#SUPERSEDED}. The later ticket observes the combined composition.
 * Superseding a ticket does not itself retire an addition: the combined candidate may still publish that
 * implementation, whose callback then follows its ordinary live lifetime.
 * Publication is transactional: a candidate composition becomes active as one unit only after successful
 * compilation. Failure leaves the last ready composition active and fails the final ticket for that
 * candidate; it does not partially publish additions or removals. Additions introduced by the failed
 * candidate are abandoned, permanently resolve to their fallback, and retire their accepted data. Drops
 * in the failed candidate do not take effect and may be requested again. A later request starts from the
 * last ready composition, so failed source never poisons an unrelated future composition.
 *
 * <p>All methods are thread-safe. They accept the requested composition change synchronously; compilation
 * and publication proceed asynchronously as the returned ticket describes. Adding a surface or volume
 * transfers its data-root callback only after synchronous acceptance. Each accepted callback is scheduled
 * exactly once and never inline; a rejected add leaves the callback caller-owned.
 */
public interface ProgramChannel {
    /**
     * Add a surface implementation and its optional coverage type, returning a typed id usable immediately.
     * Closest-hit evaluates the surface type; a geometry using {@code CoveragePolicy.Cutout} requires and
     * evaluates the separately named, narrow coverage type, while opaque-only surfaces omit it. Mesh
     * geometries in this contribution select the returned id directly. Synchronous acceptance takes
     * ownership of the definition's data-root retirement callback, including when compilation later fails
     * or is superseded.
     *
     * @throws IllegalStateException if either present qualified type name is already associated with a
     *         different shader source
     */
    <I, B, N> ProgramUpdate<SurfaceId<B, N>> addSurface(SurfaceDefinition<I, B, N> definition);

    /**
     * Stop using a surface implementation.
     *
     * <p>The id stops resolving at the next program publication boundary. Geometry which still names it
     * then uses the visible error surface; those non-owning references do not delay removal. The retirement
     * callback accepted with the definition runs after no active or in-flight program can execute the
     * implementation and no submitted GPU work can read its implementation data.
     *
     * @throws IllegalStateException if this surface is not live or awaiting a previously accepted drop
     */
    ProgramTicket dropSurface(SurfaceId<?, ?> surface);

    /**
     * Add a volume implementation, returning a typed id usable immediately. A mesh geometry may use the id as
     * its interior slot independently of whether that boundary also has a visible surface. Synchronous
     * acceptance takes ownership of the definition's data-root retirement callback, including when
     * compilation later fails or is superseded.
     *
     * @throws IllegalStateException if the qualified type name is already associated with different shader
     *         source
     */
    <I, B, N> ProgramUpdate<VolumeId<B, N>> addVolume(VolumeDefinition<I, B, N> definition);

    /**
     * Stop using a volume implementation.
     *
     * <p>The id stops resolving at the next program publication boundary. Geometry which still names it
     * then bounds vacuum; those non-owning references do not delay removal. The retirement callback accepted
     * with the definition runs after no active or in-flight program can execute the implementation and no
     * submitted GPU work can read its implementation data.
     *
     * @throws IllegalStateException if this volume is not live or awaiting a previously accepted drop
     */
    ProgramTicket dropVolume(VolumeId<?, ?> volume);

    /**
     * Add an environment implementation and its required scene-binding data schema. A scene names one;
     * several may be live at once. For an error-free
     * switch, wait for the returned ticket and then call {@link SceneHandle#setEnvironment} with a binding
     * that owns the new binding data's retirement callback. Environment implementations have no separate
     * data root or retirement callback.
     */
    <B> ProgramUpdate<EnvironmentId<B>> addEnvironment(EnvironmentDefinition<B> definition);

    /**
     * Stop using an environment. Scene bindings which still name the id switch to the visible error
     * environment and retire their own data callbacks; those non-owning references do not delay removal.
     *
     * @throws IllegalStateException if this environment is not live or awaiting a previously accepted drop
     */
    ProgramTicket dropEnvironment(EnvironmentId<?> environment);

}
