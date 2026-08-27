package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.material.SurfaceId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentId;

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
 * composition that implements it. Callers which require an atomic visible switchover register a completion
 * callback and publish materials or scenes only after readiness. Callers that accept the visible error
 * implementation may publish immediately.
 *
 * <p><b>Nothing here is batched, because nothing here is visible on its own.</b> Atomicity exists so that no
 * frame shows a half-applied change, and only scene content is in a frame's picture — a table entry nothing
 * names changes nothing. The visible switchover happens when geometry starts naming the new thing, which is
 * where the atomic batch already is. This is the resource-reload story unchanged: add the new
 * implementations, register the new materials, resubmit the affected meshes in <em>one</em> atomic batch,
 * drop the old ones.
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
 * and publication proceed asynchronously as the returned ticket describes.
 */
public interface ProgramChannel {
    /**
     * Add a surface implementation and the coverage type that goes with it, returning an id usable
     * immediately. Both are required: closest-hit evaluates the surface type while any-hit and opacity
     * micromap classification evaluate the separately named, narrow coverage type.
     *
     * @throws IllegalStateException if another live implementation declares this type name from a different
     *         module — extension shader type names are global to the composition
     */
    ProgramUpdate<SurfaceId> addSurface(SurfaceDefinition definition);

    /**
     * Stop using a surface implementation, and learn when whatever the source associated with it is free.
     *
     * <p>Nothing is freed at the call, and dropping while materials still name it is neither an error nor
     * rejected — the same contract dropping a material has. {@code retired} runs once nothing names it and
     * no in-flight program contains it; until then those materials shade as the visible error surface.
     * The callback follows the serialized, non-blocking, must-not-throw policy of retained callbacks.
     */
    ProgramTicket dropSurface(SurfaceId surface, Runnable retired);

    /** Add an environment implementation. A scene names one; several may be live at once. */
    ProgramUpdate<EnvironmentId> addEnvironment(ShaderDefinition definition);

    /**
     * Stop using an environment. {@code retired} runs once no scene names it and follows the serialized,
     * non-blocking, must-not-throw retained-callback policy.
     */
    ProgramTicket dropEnvironment(EnvironmentId environment, Runnable retired);

    /**
     * Add a projected surface modifier. Nothing names one: every live modifier runs, in the order they were
     * added, and geometry without the receiver semantic pays for no dispatch.
     */
    ProgramUpdate<SurfaceModifierId> addSurfaceModifier(ShaderDefinition definition);

    /**
     * Stop running a modifier. {@code retired} runs once no in-flight program contains it and follows the
     * serialized, non-blocking, must-not-throw retained-callback policy.
     */
    ProgramTicket dropSurfaceModifier(SurfaceModifierId modifier, Runnable retired);

}
