package dev.comfyfluffy.caustica.api.resource;

/**
 * Stable identity for a provider-owned resource graph, independent of its addresses and descriptor indices.
 * Values carrying this identity must be accompanied by an ownership claim until the consumer retains it.
 */
public interface ResourceRef {
    /** Retain the live resource graph. A released graph cannot be acquired again. */
    ResourceOwner retain();

    /** Inline values and storage whose lifetime is supplied by another enclosing owner. */
    static ResourceRef none() { return NoResourceRef.INSTANCE; }
}

enum NoResourceRef implements ResourceRef, ResourceOwner {
    INSTANCE;
    @Override public ResourceRef reference() { return this; }
    @Override public ResourceOwner retain() { return this; }
    @Override public void close() { }
}
