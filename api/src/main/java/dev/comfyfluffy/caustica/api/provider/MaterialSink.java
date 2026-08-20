package dev.comfyfluffy.caustica.api.provider;

/** Receives one material source's texture registrations and material contributions for the current epoch. */
public interface MaterialSink extends TextureRegistrar {
    /** Define a textureless material addressable by geometry sources. */
    void define(MaterialDefinition definition);

    /** Contribute an ordered override rule. */
    void submit(MaterialRule rule);

    /** Contribute one GPU texture resource compiled with this source's materials for the resource epoch. */
    void submitResource(MaterialTextureResource resource);
}
