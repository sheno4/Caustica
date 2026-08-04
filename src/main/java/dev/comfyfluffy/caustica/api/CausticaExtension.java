package dev.comfyfluffy.caustica.api;

/** Fabric entry point for extensions that contribute Caustica features. */
public interface CausticaExtension {
    void register(CausticaRegistry registry);
}
