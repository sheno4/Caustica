package dev.comfyfluffy.caustica.api.vulkan;

import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * Encodes {@code VK_EXT_descriptor_heap} descriptors into an allocated range.
 *
 * <p>Each call validates the range kind and relative slot index, invokes the Vulkan descriptor
 * writer, and makes non-coherent host writes visible before returning. Publication is still the caller's
 * responsibility: do not make an overwritten descriptor reachable by work already in flight. Allocate a
 * replacement range, publish its first index from a pass, and retire the old range instead.
 * Descriptor-info structs and everything reachable through their pointers need only remain valid for the
 * duration of the call; the encoded bytes do not retain those host pointers.
 */
public interface GpuDescriptorWriter {
    /** Encode one sampler descriptor at an index relative to a sampler range. */
    void writeSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination, int relativeIndex,
                      VkSamplerCreateInfo sampler);

    /**
     * Encode one image, buffer, or other resource descriptor at an index relative to a resource range.
     * {@link VkResourceDescriptorInfoEXT#type()} selects the encoded descriptor type.
     */
    void writeResource(GpuDescriptorRange<GpuDescriptorIndex.Resource> destination, int relativeIndex,
                       VkResourceDescriptorInfoEXT resource);

    /**
     * Encode an extension-owned acceleration structure. LWJGL represents the non-dispatchable
     * {@code VkAccelerationStructureKHR} handle as {@code long}; it has no typed wrapper class.
     */
    void writeAccelerationStructure(
            GpuDescriptorRange<GpuDescriptorIndex.Resource> destination,
            int relativeIndex, long accelerationStructure
    );
}
