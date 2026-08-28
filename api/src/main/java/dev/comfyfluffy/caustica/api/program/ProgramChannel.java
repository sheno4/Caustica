package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.scene.SceneEnvironment;
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
 * <li>An environment becomes selected when a {@link SceneHandle} receives a {@link SceneEnvironment}
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
 * <p>Tickets do not prevent renderer-wide coalescing. Additions and removals made before the same program
 * boundary may share one compilation and complete together.
 *
 * <p>All methods are thread-safe. They accept the requested composition change synchronously; compilation
 * and publication proceed asynchronously as the returned ticket describes. A drop transfers its callback
 * only after synchronous acceptance. Each accepted callback is scheduled exactly once and never inline;
 * a rejected or duplicate drop leaves the callback caller-owned.
 */
public interface ProgramChannel {
    /**
     * Add a surface implementation and the coverage type that goes with it, returning an id usable
     * immediately. Both are required: closest-hit evaluates the surface type while any-hit and opacity
     * micromap classification evaluate the separately named, narrow coverage type. Mesh geometries in this
     * contribution select the returned id directly.
     *
     * @throws IllegalStateException if another live implementation declares this type name from a different
     *         module — extension shader type names are global to the composition
     */
    ProgramUpdate<SurfaceId> addSurface(SurfaceDefinition definition);

    /**
     * Stop using a surface implementation, and learn when data reachable from its source-owned root is free.
     *
     * <p>The id stops resolving at the next program publication boundary. Geometry which still names it
     * then uses the visible error surface; those non-owning references do not delay removal. {@code retired}
     * runs exactly once after no active or in-flight program can execute the implementation and no submitted
     * GPU work can read its data. A rejected call does not take the callback.
     *
     * @throws IllegalStateException if this surface was already dropped
     */
    ProgramTicket dropSurface(SurfaceId surface, Runnable retired);

    /**
     * Add a volume implementation, returning an id usable immediately. A mesh geometry may use the id as
     * its interior slot independently of whether that boundary also has a visible surface.
     *
     * @throws IllegalStateException if another live implementation declares this type name from a different
     *         module — extension shader type names are global to the composition
     */
    ProgramUpdate<VolumeId> addVolume(VolumeDefinition definition);

    /**
     * Stop using a volume implementation, and learn when its source-owned data is free.
     *
     * <p>The id stops resolving at the next program publication boundary. Geometry which still names it
     * then bounds vacuum; those non-owning references do not delay removal. {@code retired} runs exactly
     * once after no active or in-flight program can execute the implementation and no submitted GPU work
     * can read its data. A rejected call does not take the callback.
     *
     * @throws IllegalStateException if this volume was already dropped
     */
    ProgramTicket dropVolume(VolumeId volume, Runnable retired);

    /**
     * Add an environment implementation. A scene names one; several may be live at once. For an error-free
     * switch, wait for the returned ticket and then call {@link SceneHandle#setEnvironment} with a binding
     * that owns the new parameter data's retirement callback.
     */
    ProgramUpdate<EnvironmentId> addEnvironment(ShaderDefinition definition);

    /**
     * Stop using an environment. Scene bindings which still name the id switch to the visible error
     * environment and retire their own parameter callbacks; those non-owning references do not delay
     * removal. {@code retired} runs after no active or in-flight program can execute the implementation and
     * follows the serialized, non-blocking, must-not-throw retained-callback policy.
     *
     * @throws IllegalStateException if this environment was already dropped
     */
    ProgramTicket dropEnvironment(EnvironmentId environment, Runnable retired);

}
