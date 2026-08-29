package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One environment implementation and the schema required by each scene binding which selects it.
 *
 * @param <B> source-defined marker for the scene binding data consumed by the implementation
 */
public record EnvironmentDefinition<B>(ShaderDefinition implementation, ShaderDataType<B> bindingDataType) {
    public EnvironmentDefinition {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(bindingDataType, "bindingDataType");
    }
}
