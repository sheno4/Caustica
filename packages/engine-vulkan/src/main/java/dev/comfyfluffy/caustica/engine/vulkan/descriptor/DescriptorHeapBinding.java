package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

/** Validated native storage and layout passed to the command-binding seam. */
public record DescriptorHeapBinding(DescriptorHeapLayout layout, DescriptorHeapStorage storage) {
    public DescriptorHeapBinding {
        if (layout.kind() != storage.kind()) {
            throw new IllegalArgumentException("descriptor heap storage kind does not match its layout");
        }
        layout.validateStorage(storage.deviceRange());
        if (storage.mappedAddress() == 0) {
            throw new IllegalArgumentException("descriptor heap storage must be host mapped");
        }
    }
}
