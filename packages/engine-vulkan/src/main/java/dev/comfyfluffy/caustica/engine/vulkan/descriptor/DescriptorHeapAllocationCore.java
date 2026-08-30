package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeapProperties;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;

/** Typed resource/sampler allocation core shared by the engine and extension-facing heap service. */
public final class DescriptorHeapAllocationCore {
    private final DescriptorHeapAllocator<GpuDescriptorIndex.Resource> resources;
    private final DescriptorHeapAllocator<GpuDescriptorIndex.Sampler> samplers;

    public DescriptorHeapAllocationCore(
            GpuDescriptorHeapProperties properties,
            long samplerDescriptorStrideBytes,
            int resourceDescriptorCapacity,
            int samplerDescriptorCapacity,
            long resourceHeapAlignmentBytes,
            long samplerHeapAlignmentBytes,
            long minimumResourceReservedBytes,
            long minimumSamplerReservedBytes,
            long maximumResourceHeapBytes,
            long maximumSamplerHeapBytes
    ) {
        DescriptorHeapLayout resourceLayout = DescriptorHeapLayout.create(
                DescriptorHeapKind.RESOURCE,
                properties.resourceDescriptorStrideBytes(), resourceHeapAlignmentBytes,
                minimumResourceReservedBytes, maximumResourceHeapBytes,
                resourceDescriptorCapacity, properties.maximumResourceAllocation());
        DescriptorHeapLayout samplerLayout = DescriptorHeapLayout.create(
                DescriptorHeapKind.SAMPLER,
                samplerDescriptorStrideBytes, samplerHeapAlignmentBytes,
                minimumSamplerReservedBytes, maximumSamplerHeapBytes,
                samplerDescriptorCapacity, properties.maximumSamplerAllocation());
        resources = new DescriptorHeapAllocator<>(resourceLayout, GpuDescriptorIndex.Resource::new);
        samplers = new DescriptorHeapAllocator<>(samplerLayout, GpuDescriptorIndex.Sampler::new);
    }

    public DescriptorHeapAllocator<GpuDescriptorIndex.Resource> resources() {
        return resources;
    }

    public DescriptorHeapAllocator<GpuDescriptorIndex.Sampler> samplers() {
        return samplers;
    }

    public DescriptorHeapAllocation<GpuDescriptorIndex.Resource> allocateResources(int descriptorCount) {
        return resources.allocate(descriptorCount);
    }

    public DescriptorHeapAllocation<GpuDescriptorIndex.Sampler> allocateSamplers(int descriptorCount) {
        return samplers.allocate(descriptorCount);
    }
}
