package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;

import java.util.Objects;

/**
 * One environment implementation and its scene-specific parameter word.
 *
 * <p>{@code retired} belongs to the binding being introduced. It runs after replacement, scene removal,
 * implementation drop, or session teardown once no submitted GPU work can select or read this binding.
 * Dropping the implementation makes the scene use the visible error environment; the stale id does not
 * retain either implementation or parameter data. Retirement callbacks are serialized by the session and
 * must return promptly without throwing.
 */
public record SceneEnvironment(EnvironmentId implementation, long parameters, Runnable retired) {
    public SceneEnvironment {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(retired, "retired");
    }

    /** A binding whose source requires no retirement notification. */
    public static SceneEnvironment of(EnvironmentId implementation, long parameters) {
        return new SceneEnvironment(implementation, parameters, () -> { });
    }
}
