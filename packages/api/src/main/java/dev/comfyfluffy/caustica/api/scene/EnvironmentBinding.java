package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderData;

import java.util.Objects;

/**
 * One environment implementation and its typed scene-specific binding data. The ID and data word carry
 * the same source-defined schema, and scene mutation validates the schema token after generic erasure.
 * The environment id may have been explicitly handed off by another contribution in this render session;
 * the binding does not grant authority to remove that implementation or keep its registration alive.
 *
 * <p>{@code retired} belongs to the binding being introduced. It runs after replacement, scene removal,
 * implementation drop, or session teardown once no submitted GPU work can select or read this binding.
 * Dropping the implementation makes the scene use the visible error environment; the stale id does not
 * retain either implementation or binding data. Retirement callbacks are serialized by the session and
 * must return promptly without throwing.
 *
 * @param <B> scene-binding data schema required by the environment implementation
 */
public record EnvironmentBinding<B>(EnvironmentId<B> implementation, ShaderData<B> bindingData,
                                    Runnable retired) {
    public EnvironmentBinding {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(bindingData, "bindingData");
        Objects.requireNonNull(retired, "retired");
    }

    /** A binding whose source requires no retirement notification. */
    public static <B> EnvironmentBinding<B> of(EnvironmentId<B> implementation,
                                                ShaderData<B> bindingData) {
        return new EnvironmentBinding<>(implementation, bindingData, () -> { });
    }
}
