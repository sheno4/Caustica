package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.Objects;

/**
 * One typed 64-bit word passed unchanged to extension shader code.
 *
 * <p>The bits commonly contain a Vulkan device address, a descriptor-heap index, or packed scalars. The
 * renderer assigns them no meaning and performs no signed range validation. {@link #type()} supplies both
 * Java compile-time checking and the runtime identity used to reject mismatched retained bindings after
 * generic erasure. A non-{@link ResourceRef#none() none} resource reference identifies one immutable
 * generation containing every byte transitively reachable through the word. Updating any of those bytes
 * requires a new generation. Equal words do not imply equal resource identity. With
 * {@link ResourceRef#none()}, the word is inline or its reachable storage is covered by the callback of the
 * retained batch, binding, or definition carrying it.
 *
 * @param <T> source-defined marker for the shader-visible data schema
 */
public record ShaderData<T>(ShaderDataType<T> type, long bits, ResourceRef resource) {
    public ShaderData {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resource, "resource");
    }

    /** Creates inline/scalar data or data covered by the containing batch, binding, or definition. */
    public ShaderData(ShaderDataType<T> type, long bits) {
        this(type, bits, ResourceRef.none());
    }
}
