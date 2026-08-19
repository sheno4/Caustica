package dev.comfyfluffy.caustica.api;

/** Registration contract for extensions that contribute Caustica features. */
public interface CausticaExtension {
    void register(CausticaRegistry registry);
}
