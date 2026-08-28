package dev.comfyfluffy.caustica.api.gpu;

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
    void writeSampler(GpuDescriptorRange destination, int relativeIndex, VkSamplerCreateInfo sampler);

    /** Encode one image resource descriptor at an index relative to a resource range. */
    void writeImage(GpuDescriptorRange destination, int relativeIndex, VkResourceDescriptorInfoEXT image);

    /** Encode one buffer or acceleration-structure descriptor into a resource range. */
    void writeBuffer(GpuDescriptorRange destination, int relativeIndex, VkResourceDescriptorInfoEXT buffer);

    /**
     * Encode an extension-owned acceleration structure. LWJGL represents the non-dispatchable
     * {@code VkAccelerationStructureKHR} handle as {@code long}; it has no typed wrapper class.
     */
    void writeAccelerationStructure(
            GpuDescriptorRange destination, int relativeIndex, long accelerationStructure
    );
}
