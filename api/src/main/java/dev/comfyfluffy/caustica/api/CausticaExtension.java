package dev.comfyfluffy.caustica.api;

/**
 * Process entry point for an extension.
 */
public interface CausticaExtension {
    /**
     * Registers process-lived factories. No render session, GPU device, or retained identity is live
     * during this call; those are supplied later to each factory through a session context.
     */
    void register(CausticaApi api);
}
