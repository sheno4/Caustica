package dev.comfyfluffy.caustica.api;

/**
 * Entry point for a mod that contributes to Caustica.
 *
 * <p>Called once, after the host has installed the API and before the first frame. There is nothing to
 * hand over: everything an extension adds — implementations, passes, providers, retained data — it adds
 * through {@link CausticaApi}, on its own schedule, and may drop again at any time.
 */
public interface CausticaExtension {
    void onCausticaReady();
}
