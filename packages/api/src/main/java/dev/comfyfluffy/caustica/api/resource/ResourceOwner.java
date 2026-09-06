package dev.comfyfluffy.caustica.api.resource;

/** One independently closeable strong claim on a resource graph. */
public interface ResourceOwner extends AutoCloseable {
    /** Acquire an independent claim while this handle is open. Never consumes this handle. */
    ResourceOwner retain();

    /** Release only this claim; repeated close calls have no effect. Final release schedules the provider's destruction callback. */
    @Override void close();

    /** Inline data has no external resources to retain or destroy. */
    static ResourceOwner none() { return InlineResource.INSTANCE; }
}

enum InlineResource implements ResourceOwner {
    INSTANCE;
    @Override public ResourceOwner retain() { return this; }
    @Override public void close() { }
}
