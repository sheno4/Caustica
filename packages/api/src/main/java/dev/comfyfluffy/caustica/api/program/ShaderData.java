package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import java.util.Objects;

/**
 * An owning, typed 64-bit shader value. Construction retains its dependency without consuming the caller's
 * handle. Copies made with retain() own independent claims; close() releases only this value's claim.
 * Accepting consumers retain their own copies before returning. The renderer forwards the bits unchanged.
 */
public record ShaderData<T>(ShaderDataType<T> type, long bits, ResourceOwner resource) implements ResourceOwner {
    public ShaderData {
        Objects.requireNonNull(type, "type");
        resource = Objects.requireNonNull(resource, "resource").retain();
    }

    /** Borrow this value's dependency handle for validation; ownership remains with this value. */
    public ResourceOwner resource() { return resource; }

    @Override public ShaderData<T> retain() { return new ShaderData<>(type, bits, resource); }
    @Override public void close() { resource.close(); }
}
