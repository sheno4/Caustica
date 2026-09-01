package dev.comfyfluffy.caustica.api.resource;

/**
 * Opaque lifetime identity of one source-owned resource generation.
 *
 * <p>A non-{@link #none() none} reference keeps alive the allocation graph needed to read the API value
 * carrying it. The identity is independent of every device address, descriptor index, or packed value
 * which may refer to the resource, so equal pointer bits do not establish equal lifetime identity.
 * Renderer APIs retain this reference when accepting work; extension code cannot acquire or release
 * renderer borrows directly. Retained source data is immutable for the generation unless its API contract
 * explicitly defines frame-ordered mutation.
 */
public interface ResourceRef {
    /**
     * Canonical marker for a value whose reachable storage needs no independent renderer lifetime.
     *
     * <p>Use this only for inline scalars or storage guaranteed to live for the entire render session.
     */
    static ResourceRef none() {
        return NoResourceRef.INSTANCE;
    }
}

enum NoResourceRef implements ResourceRef {
    INSTANCE
}
