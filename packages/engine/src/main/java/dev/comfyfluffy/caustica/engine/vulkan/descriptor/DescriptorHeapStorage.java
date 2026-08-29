package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

/** Native heap memory supplied by the Vulkan device integration. */
public interface DescriptorHeapStorage extends AutoCloseable {
    DescriptorHeapKind kind();

    long deviceAddress();

    long mappedAddress();

    long sizeBytes();

    void flush(long byteOffset, long byteSize);

    @Override
    void close();
}
