package dev.comfyfluffy.caustica.spi.vulkan;

import org.lwjgl.vulkan.VkQueue;

/** Raw queue handle and family index supplied by a Vulkan renderer host. */
public record VulkanQueueRef(VkQueue queue, int familyIndex) {
}
