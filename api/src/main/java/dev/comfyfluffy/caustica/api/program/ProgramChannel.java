package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.material.SurfaceId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentId;
import dev.comfyfluffy.caustica.api.scene.light.EmissionProfileId;

/**
 * Everything an extension compiles into the world ray-tracing program, reached from
 * {@link CausticaApi#program()}.
 *
 * <p>An implementation is not declared once at startup — it is added and dropped at any time, which is what
 * makes switching a feature off mean <em>not present</em> rather than present-but-disabled. There is no gate
 * to consult and no way to name something that is not there.
 *
 * <h2>Adding is synchronous; there are no batches</h2>
 *
 * The same shape as {@link dev.comfyfluffy.caustica.api.material.MaterialChannel#register}, and for the same
 * reason: immediacy is what keeps ordering between channels from becoming a concept. A material registered
 * after {@link #addSurface} returns can always name the result.
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
 * <p>Until that rebuild lands, an id names something that is not yet compiled: a material naming a new
 * surface shades as the visible error surface for those frames. That is the cost of keeping the id usable
 * immediately, and it is paid where nothing is looking, because every realistic trigger — startup, a
 * setting, a pack reload — already sits on a reload boundary.
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
    SurfaceId addSurface(SurfaceDefinition definition);

    /**
     * Stop using a surface implementation, and learn when whatever the source associated with it is free.
     *
     * <p>Nothing is freed at the call, and dropping while materials still name it is neither an error nor
     * rejected — the same contract dropping a material has. {@code retired} runs once nothing names it and
     * no in-flight program contains it; until then those materials shade as the visible error surface.
     */
    void dropSurface(SurfaceId surface, Runnable retired);

    /** Add an environment implementation. A scene names one; several may be live at once. */
    EnvironmentId addEnvironment(ShaderDefinition definition);

    /** Stop using an environment. {@code retired} runs once no scene names it. */
    void dropEnvironment(EnvironmentId environment, Runnable retired);

    /** Add an emission profile a light descriptor can name. */
    EmissionProfileId addEmissionProfile(ShaderDefinition definition);

    /** Stop using an emission profile. {@code retired} runs once no light descriptor names it. */
    void dropEmissionProfile(EmissionProfileId profile, Runnable retired);

    /**
     * Add a projected surface modifier. Nothing names one: every live modifier runs, in the order they were
     * added, and geometry without the receiver semantic pays for no dispatch.
     */
    SurfaceModifierId addSurfaceModifier(ShaderDefinition definition);

    /** Stop running a modifier. {@code retired} runs once no in-flight program contains it. */
    void dropSurfaceModifier(SurfaceModifierId modifier, Runnable retired);

    /**
     * Anchor a Slang module into the program outside the generic composition mechanism. Needed by a module
     * a pass's own binding declarations live in, since Slang forbids a generic entry point's type-parameter
     * implementation from declaring global shader parameters itself. The engine imports every anchored
     * module into one generated module that every composition-generic engine stage imports unconditionally,
     * so the pass never needs the engine to know its resource names — only that the module exists.
     */
    ResourceModuleId addResourceModule(ModuleDefinition definition);

    /** Stop anchoring a module. {@code retired} runs once no in-flight program contains it. */
    void dropResourceModule(ResourceModuleId module, Runnable retired);
}
