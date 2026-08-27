package dev.comfyfluffy.caustica.api.session;

/** Process-time registration of render-session contribution factories. */
public interface RenderSessionChannel {
    /**
     * Registers a factory for the lifetime of the returned registration. The host calls it once for each
     * render session and never reuses a contribution returned for an earlier session.
     */
    RenderSessionRegistration add(RenderSessionFactory factory);
}
