package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One volume implementation and its extension-owned session data root.
 *
 * <p>{@code implementationData} reaches the implementation unchanged. It is commonly a device address for a table of
 * density fields and texture descriptors, but may be any packed 64-bit value. The binding and instance
 * schema tokens declare typed binding and instance words for geometry interiors and captured spatial media.
 *
 * @param <B> geometry-slot binding data schema
 * @param <N> mesh-placement instance data schema
 */
public record VolumeDefinition<B, N>(ShaderDefinition implementation, ShaderData<?> implementationData,
                                     ShaderDataType<B> bindingDataType,
                                     ShaderDataType<N> instanceDataType) {
    public VolumeDefinition {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(implementationData, "implementationData");
        Objects.requireNonNull(bindingDataType, "bindingDataType");
        Objects.requireNonNull(instanceDataType, "instanceDataType");
    }

}
