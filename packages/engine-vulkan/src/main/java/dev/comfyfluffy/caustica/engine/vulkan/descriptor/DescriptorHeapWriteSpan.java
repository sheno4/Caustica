package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

/** Validated destination of one descriptor encoding operation. */
public record DescriptorHeapWriteSpan(
        long byteOffset,
        long byteSize
) {
}
