package dev.comfyfluffy.caustica.engine.program;

/** Receives callback or backend failures on the engine callback path. It must not throw. */
@FunctionalInterface
public interface ProgramEngineFailureHandler {
    void report(Throwable failure);
}
