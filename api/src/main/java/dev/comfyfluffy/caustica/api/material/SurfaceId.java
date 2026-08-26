package dev.comfyfluffy.caustica.api.material;

import dev.comfyfluffy.caustica.api.ProgramId;

/**
 * A surface implementation compiled into the active world program, resolved by
 * {@link MaterialChannel#surface}. Opaque — see {@link ProgramId}.
 */
public interface SurfaceId extends ProgramId {
}
