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
 * @param <B> scene-binding data schema required by the environment implementation
 */
public record EnvironmentBinding<B>(EnvironmentId<B> implementation, ShaderData<B> bindingData) {
    public EnvironmentBinding {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(bindingData, "bindingData");
    }

}
