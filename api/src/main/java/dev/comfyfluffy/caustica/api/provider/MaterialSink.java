package dev.comfyfluffy.caustica.api.provider;

/** Receives one material source's texture registrations and material contributions for the current epoch. */
public interface MaterialSink extends TextureRegistrar {
    /** Define a material addressable by geometry sources. */
    void define(MaterialDefinition definition);

    /** Register a non-throwing action run after this source's definitions and textures commit successfully. */
    void onCommit(Runnable action);
}
