package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.Objects;

/**
 * One environment implementation and its typed scene-specific binding data. The ID and data word carry
 * the same source-defined schema, and scene mutation validates the schema token after generic erasure.
 * The environment id may have been explicitly handed off by another contribution in this render session;
 * the binding does not grant authority to remove that implementation or keep its registration alive.
 *
 * <p>{@code retired} belongs to the binding being introduced. It is the lifetime callback for the retained
 * logical binding and for storage reached through {@code bindingData} when it carries
 * {@link ResourceRef#none()}. It runs after replacement, scene removal, channel invalidation, or session
 * teardown once no submitted GPU work can select or read this binding. Dropping the implementation makes
 * new frames use the visible error environment, while the logical binding and its NONE-owned storage remain
 * retained until one of those terminal binding events. Retirement callbacks are serialized by the session
 * and must return promptly without throwing.
 *
 * <p>A non-NONE {@link ResourceGeneration} referenced by {@code bindingData} has an independent retirement
 * callback and may be shared by other bindings, operations, or batches. This binding's {@code retired}
 * callback does not report that generation's retirement. Dropping that generation independently makes new
 * frames use the error environment when they can no longer acquire it; previously accepted frames keep
 * their existing borrows.
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
