package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.RetainedId;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;

/**
 * An environment implementation compiled into the world program, issued by
 * {@link ProgramChannel#addEnvironment}. A scene names one. Opaque — see {@link RetainedId}.
 */
public interface EnvironmentId extends RetainedId {
}
