package dev.comfyfluffy.caustica.api.provider;

/** Receives append-only texture contributions for one scene provider and resource epoch. */
@FunctionalInterface
public interface TextureSink {
    /**
     * Associate a source-local texture reference with its content. A provider submits each reference at most
     * once per resource epoch, before submitting geometry that uses it.
     */
    void submit(SceneMesh.TextureReference reference, TextureResource resource);
}
