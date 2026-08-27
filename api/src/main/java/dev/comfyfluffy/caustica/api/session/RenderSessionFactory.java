package dev.comfyfluffy.caustica.api.session;

/** Creates one extension contribution for one live render session. */
@FunctionalInterface
public interface RenderSessionFactory {
    /**
     * Creates and registers a fresh contribution using only services from {@code context}.
     *
     * <p>If this method throws, the host tears down every object already added through the context and no
     * contribution callbacks follow.
     */
    RenderSessionContribution open(RenderSessionContext context);
}
