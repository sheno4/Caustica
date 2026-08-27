package dev.comfyfluffy.caustica.api.gpu;

/** Shader-visible interpretation used when encoding an image into the resource heap. */
public enum GpuImageDescriptorKind {
    SAMPLED,
    READ_ONLY_STORAGE,
    READ_WRITE_STORAGE
}
