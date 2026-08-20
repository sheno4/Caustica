package dev.comfyfluffy.caustica.api.provider;

/** Receives a material source's ordered rules for the current resource epoch. */
public interface MaterialSink {
    /** Define a textureless material addressable by geometry sources. */
    void define(MaterialDefinition definition);

    /** Contribute an ordered override rule. */
    void submit(MaterialRule rule);

    /** Contribute one GPU texture resource compiled with this source's materials for the resource epoch. */
    void submitResource(MaterialTextureResource resource);
}
