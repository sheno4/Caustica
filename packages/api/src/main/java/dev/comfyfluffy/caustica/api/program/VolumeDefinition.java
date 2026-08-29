package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One volume implementation and its extension-owned session data root.
 *
 * <p>{@code implementationData} reaches the implementation unchanged. It is commonly a device address for a table of
 * density fields and texture descriptors, but may be any packed 64-bit value. The binding and instance
 * schema tokens declare the typed words accepted by volume slots and placements and are validated when
 * geometry is submitted. Keep resources reachable
 * from this word alive until {@code retired} runs. The callback runs exactly once after the owning
 * registration is removed, its compilation fails, or its session ends, once no active or
 * in-flight program can execute it and no submitted GPU work can read the root. Retirement callbacks are
 * serialized by the session, never run inline with {@link ProgramBuilder#volume}, and must return
 * promptly without throwing.
 *
 * @param <I> implementation data schema
 * @param <B> geometry-slot binding data schema
 * @param <N> mesh-placement instance data schema
 */
public record VolumeDefinition<I, B, N>(ShaderDefinition implementation, ShaderData<I> implementationData,
                                        ShaderDataType<B> bindingDataType,
                                        ShaderDataType<N> instanceDataType,
                                        Runnable retired) {
    public VolumeDefinition {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(implementationData, "implementationData");
        Objects.requireNonNull(bindingDataType, "bindingDataType");
        Objects.requireNonNull(instanceDataType, "instanceDataType");
        Objects.requireNonNull(retired, "retired");
    }

    /** A definition whose source requires no separate retirement notification. */
    public static <I, B, N> VolumeDefinition<I, B, N> of(
            ShaderDefinition implementation, ShaderData<I> implementationData,
            ShaderDataType<B> bindingDataType, ShaderDataType<N> instanceDataType) {
        return new VolumeDefinition<>(implementation, implementationData, bindingDataType,
                instanceDataType, () -> { });
    }
}
