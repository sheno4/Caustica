package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.List;

/** Vulkan encoding seam used after allocation and destination validation. */
public interface DescriptorHeapNativeWriter {
    void writeSampler(long destinationHostAddress, VkSamplerCreateInfo sampler);

    void writeSamplers(long destinationHostAddress, VkSamplerCreateInfo.Buffer samplers);

    void writeImages(long destinationHostAddress, List<GpuDescriptorWriter.ImageWrite> images);

    void writeResource(long destinationHostAddress, VkResourceDescriptorInfoEXT resource);

    void writeAccelerationStructure(long destinationHostAddress, long accelerationStructure);
}
