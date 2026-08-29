package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Vulkan command seam that binds the one resource heap and one sampler heap to a command buffer. */
@FunctionalInterface
public interface DescriptorHeapCommandBinder {
    void bind(VkCommandBuffer commandBuffer, DescriptorHeapBinding resources, DescriptorHeapBinding samplers);
}
