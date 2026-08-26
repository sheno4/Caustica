package dev.comfyfluffy.caustica.api.scene.light;

import dev.comfyfluffy.caustica.api.ProgramId;

/**
 * An emission profile compiled into the active world program, resolved by
 * {@link LightChannel#emissionProfile}. Opaque — see {@link ProgramId}.
 */
public interface EmissionProfileId extends ProgramId {
}
