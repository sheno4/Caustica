package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Vulkan encoding seam used after allocation and destination validation. */
public interface DescriptorHeapNativeWriter {
    void writeSampler(long destinationHostAddress, VkSamplerCreateInfo sampler);

    void writeResource(long destinationHostAddress, VkResourceDescriptorInfoEXT resource);

    void writeAccelerationStructure(long destinationHostAddress, long accelerationStructure);
}
