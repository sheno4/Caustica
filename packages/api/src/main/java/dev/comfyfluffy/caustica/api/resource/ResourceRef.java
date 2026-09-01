package dev.comfyfluffy.caustica.api.resource;

/**
 * Opaque identity of one immutable, source-owned resource generation.
 *
 * <p>A non-{@link #none() none} reference covers every byte transitively reachable through the API value
 * carrying it. Those bytes remain unchanged for the generation's lifetime; updates use a new generation.
 * The identity is independent of every device address, descriptor index, or packed value which may refer
 * to the resource, so equal pointer bits do not establish equal lifetime identity. Renderer APIs retain
 * this reference when accepting work; extension code cannot acquire or release renderer borrows directly.
 */
public interface ResourceRef {
    /**
     * Canonical marker for a value whose reachable storage needs no independent renderer lifetime.
     *
     * <p>Use this for inline scalars and for storage whose containing retained value supplies the lifetime.
     */
    static ResourceRef none() {
        return NoResourceRef.INSTANCE;
    }
}

enum NoResourceRef implements ResourceRef {
    INSTANCE
}
