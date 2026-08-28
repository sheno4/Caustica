package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.retained.RetainedId;

/**
 * An environment implementation compiled into the world program, issued by
 * {@link ProgramChannel#addEnvironment}. A scene names one. Opaque — see {@link RetainedId}.
 */
public interface EnvironmentId extends RetainedId {
}
