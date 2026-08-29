package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One typed 64-bit word passed unchanged to extension shader code.
 *
 * <p>The bits commonly contain a Vulkan device address, a descriptor-heap index, or packed scalars. The
 * renderer assigns them no meaning and performs no signed range validation. {@link #type()} supplies both
 * Java compile-time checking and the runtime identity used to reject mismatched retained bindings after
 * generic erasure. Resource ownership belongs to the definition, binding, or retained batch carrying this
 * value; this value object owns nothing.
 *
 * @param <T> source-defined marker for the shader-visible data schema
 */
public record ShaderData<T>(ShaderDataType<T> type, long bits) {
    public ShaderData {
        Objects.requireNonNull(type, "type");
    }
}
