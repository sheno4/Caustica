package dev.comfyfluffy.caustica.engine.scene;

/** Receives retained retirement callback failures. It must not throw. */
@FunctionalInterface
public interface SceneRetirementFailureHandler {
    void report(Throwable failure);
}
