package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.Objects;

/**
 * One typed 64-bit word passed unchanged to extension shader code.
 *
 * <p>The bits commonly contain a Vulkan device address, a descriptor-heap index, or packed scalars. The
 * renderer assigns them no meaning and performs no signed range validation. {@link #type()} supplies both
 * Java compile-time checking and the runtime identity used to reject mismatched retained bindings after
 * generic erasure. A non-{@link ResourceRef#none() none} resource reference identifies the generation
 * keeping alive every allocation needed to interpret the word. Retained source data is immutable for that
 * generation; equal words do not imply equal resource identity. With {@link ResourceRef#none()}, the word
 * must be inline or its reachable storage must live for the entire render session.
 *
 * @param <T> source-defined marker for the shader-visible data schema
 */
public record ShaderData<T>(ShaderDataType<T> type, long bits, ResourceRef resource) {
    public ShaderData {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resource, "resource");
    }
}
