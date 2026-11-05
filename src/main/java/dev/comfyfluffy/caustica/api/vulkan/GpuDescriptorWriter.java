package dev.comfyfluffy.caustica.api.vulkan;

import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.List;
import java.util.Objects;

/**
 * Registers immutable native resource views in an allocated local-handle range.
 *
 * <p>Each call creates the required native view or sampler and associates it with the relative slot.
 * Dispatches resolve those local handles into ordinary Vulkan descriptor sets. Publication is still the
 * caller's responsibility: allocate a replacement range, publish its first index from a pass, and retain
 * the old range until its GPU users retire.
 * Descriptor-info structs and everything reachable through their pointers need only remain valid for the
 * duration of the call; the registry does not retain those host pointers.
 */
public interface GpuDescriptorWriter {
    /** One typed image descriptor in a contiguous resource-write batch. */
    record ImageWrite(GpuImageDescriptorKind kind, VkImageDescriptorInfoEXT descriptor) {
        public ImageWrite {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Encode one sampler descriptor at an index relative to a sampler range. */
    void writeSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination, int relativeIndex,
                      VkSamplerCreateInfo sampler);

    /** Encode contiguous sampler descriptors starting at an index relative to a sampler range. */
    void writeSamplers(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination, int relativeIndex,
                       VkSamplerCreateInfo.Buffer samplers);

    /** Encode contiguous sampled or storage image descriptors into a resource range. */
    void writeImages(GpuDescriptorRange<GpuDescriptorIndex.Resource> destination, int relativeIndex,
                     List<ImageWrite> images);

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
