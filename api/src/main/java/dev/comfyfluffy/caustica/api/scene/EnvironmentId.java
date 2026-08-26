package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.ProgramId;

/**
 * An environment implementation compiled into the active world program, resolved by
 * {@link SceneChannel#environment}. Opaque — see {@link ProgramId}.
 */
public interface EnvironmentId extends ProgramId {
}
