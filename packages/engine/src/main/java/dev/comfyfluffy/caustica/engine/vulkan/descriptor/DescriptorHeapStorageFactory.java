package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

/** Device integration seam that creates native storage for a validated heap layout. */
@FunctionalInterface
public interface DescriptorHeapStorageFactory {
    DescriptorHeapStorage create(DescriptorHeapLayout layout, String label);
}
