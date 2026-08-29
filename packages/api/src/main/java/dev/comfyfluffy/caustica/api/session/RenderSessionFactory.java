package dev.comfyfluffy.caustica.api.session;

/** Creates one extension contribution for one live render session. */
@FunctionalInterface
public interface RenderSessionFactory {
    /**
     * Creates and registers a fresh contribution using only services from {@code context}.
     *
     * <p>If this method throws, the host tears down every object already accepted through the context and
     * drains their retirement callbacks, but no contribution {@code stop}/{@code close} callbacks follow
     * because no contribution was returned. Raw Vulkan/VMA allocations and other extension-owned resources
     * remain the factory's responsibility until a contribution or pass owning them has been successfully
     * registered; the factory must release partially constructed resources before propagating an exception.
     */
    RenderSessionContribution open(RenderSessionContext context);
}
