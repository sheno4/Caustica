package dev.comfyfluffy.caustica.api.scene.light;

import dev.comfyfluffy.caustica.api.RetainedId;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;

/**
 * An emission profile compiled into the world program, issued by
 * {@link ProgramChannel#addEmissionProfile}. A {@link LightDescriptor.Emission} names one. Opaque — see
 * {@link RetainedId}.
 */
public interface EmissionProfileId extends RetainedId {
}
