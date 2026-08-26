package dev.comfyfluffy.caustica.api.material;

import dev.comfyfluffy.caustica.api.RetainedId;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;

/**
 * A surface implementation compiled into the world program, issued by {@link ProgramChannel#addSurface}.
 * A {@link MaterialDefinition} names one. Opaque — see {@link RetainedId}.
 */
public interface SurfaceId extends RetainedId {
}
