package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.RetainedId;

/**
 * A projected surface modifier compiled into the world program, issued by
 * {@link ProgramChannel#addSurfaceModifier}. Nothing names one — every live modifier runs, in the order
 * they were added. Opaque — see {@link RetainedId}.
 */
public interface SurfaceModifierId extends RetainedId {
}
