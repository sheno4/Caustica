package dev.comfyfluffy.caustica.api.resource;

/** One independently closeable strong reference to a provider-owned resource graph. */
public interface ResourceOwner extends AutoCloseable {
    /** Stable identity passed alongside shader data or geometry addresses. */
    ResourceRef reference();

    /** Acquire another ownership claim while this claim is open. */
    ResourceOwner retain();

    /** Release this claim. Other owners and accepted GPU work remain valid. */
    @Override void close();
}
