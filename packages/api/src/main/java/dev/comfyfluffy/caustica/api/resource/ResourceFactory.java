package dev.comfyfluffy.caustica.api.resource;

/** Creates shared ownership for provider-managed GPU allocations. */
public interface ResourceFactory {
    /**
     * Create one producer claim whose final release destroys its resource graph off the render thread.
     *
     * <p>The producer finishes initialization before publishing a value carrying this reference. Accepted
     * scene entries and jobs retain their own claims. The callback runs exactly once after every claim ends.
     */
    ResourceOwner create(Runnable retired);

    /** Create an owner without a destruction callback. */
    default ResourceOwner create() {
        return create(() -> { });
    }
}
