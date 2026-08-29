package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

/** Validated destination of one descriptor encoding operation. */
public record DescriptorHeapWriteSpan(
        DescriptorHeapKind kind,
        long allocationIdentity,
        int absoluteIndex,
        long byteOffset,
        long byteSize
) {
}
