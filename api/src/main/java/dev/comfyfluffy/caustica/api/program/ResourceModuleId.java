package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.RetainedId;

/**
 * A Slang module anchored into the world program outside the generic composition mechanism, issued by
 * {@link ProgramChannel#addResourceModule}. Opaque — see {@link RetainedId}.
 */
public interface ResourceModuleId extends RetainedId {
}
