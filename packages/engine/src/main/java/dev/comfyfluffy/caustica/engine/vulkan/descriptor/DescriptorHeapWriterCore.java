package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorWriter;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Validates typed allocation destinations before native descriptor encoding and storage flushing. */
public final class DescriptorHeapWriterCore implements GpuDescriptorWriter {
    private final DescriptorHeapAllocationCore allocations;
    private final DescriptorHeapStorage resourceStorage;
    private final DescriptorHeapStorage samplerStorage;
    private final DescriptorHeapNativeWriter nativeWriter;

    public DescriptorHeapWriterCore(DescriptorHeapAllocationCore allocations,
                                    DescriptorHeapStorage resourceStorage,
                                    DescriptorHeapStorage samplerStorage,
                                    DescriptorHeapNativeWriter nativeWriter) {
        this.allocations = allocations;
        this.resourceStorage = requireStorage(resourceStorage, DescriptorHeapKind.RESOURCE);
        this.samplerStorage = requireStorage(samplerStorage, DescriptorHeapKind.SAMPLER);
        this.nativeWriter = nativeWriter;
    }

    @Override
    public synchronized void writeSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination,
                                          int relativeIndex, VkSamplerCreateInfo sampler) {
        DescriptorHeapAllocation<GpuDescriptorIndex.Sampler> allocation = samplerAllocation(destination);
        allocations.samplers().withWriteSpan(allocation, relativeIndex, span -> {
            nativeWriter.writeSampler(hostAddress(samplerStorage, span), sampler);
            samplerStorage.flush(span.byteOffset(), span.byteSize());
        });
    }

    @Override
    public synchronized void writeResource(GpuDescriptorRange<GpuDescriptorIndex.Resource> destination,
                                           int relativeIndex, VkResourceDescriptorInfoEXT resource) {
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> allocation = resourceAllocation(destination);
        allocations.resources().withWriteSpan(allocation, relativeIndex, span -> {
            nativeWriter.writeResource(hostAddress(resourceStorage, span), resource);
            resourceStorage.flush(span.byteOffset(), span.byteSize());
        });
    }

    @Override
    public synchronized void writeAccelerationStructure(
            GpuDescriptorRange<GpuDescriptorIndex.Resource> destination,
            int relativeIndex, long accelerationStructure) {
        if (accelerationStructure == 0L) {
            throw new IllegalArgumentException("acceleration structure must not be null");
        }
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> allocation = resourceAllocation(destination);
        allocations.resources().withWriteSpan(allocation, relativeIndex, span -> {
            nativeWriter.writeAccelerationStructure(hostAddress(resourceStorage, span), accelerationStructure);
            resourceStorage.flush(span.byteOffset(), span.byteSize());
        });
    }

    private DescriptorHeapAllocation<GpuDescriptorIndex.Sampler> samplerAllocation(
            GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination) {
        if (!(destination instanceof DescriptorHeapAllocation<?> raw)
                || raw.kind() != DescriptorHeapKind.SAMPLER) {
            throw new IllegalArgumentException("destination is not a sampler-heap allocation");
        }
        @SuppressWarnings("unchecked")
        DescriptorHeapAllocation<GpuDescriptorIndex.Sampler> allocation =
                (DescriptorHeapAllocation<GpuDescriptorIndex.Sampler>) raw;
        return allocation;
    }

    private DescriptorHeapAllocation<GpuDescriptorIndex.Resource> resourceAllocation(
            GpuDescriptorRange<GpuDescriptorIndex.Resource> destination) {
        if (!(destination instanceof DescriptorHeapAllocation<?> raw)
                || raw.kind() != DescriptorHeapKind.RESOURCE) {
            throw new IllegalArgumentException("destination is not a resource-heap allocation");
        }
        @SuppressWarnings("unchecked")
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> allocation =
                (DescriptorHeapAllocation<GpuDescriptorIndex.Resource>) raw;
        return allocation;
    }

    private static DescriptorHeapStorage requireStorage(DescriptorHeapStorage storage, DescriptorHeapKind kind) {
        if (storage.kind() != kind) throw new IllegalArgumentException("descriptor storage kind mismatch");
        return storage;
    }

    private static long hostAddress(DescriptorHeapStorage storage, DescriptorHeapWriteSpan span) {
        return Math.addExact(storage.mappedAddress(), span.byteOffset());
    }
}
