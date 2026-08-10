package dev.comfyfluffy.caustica.rt.backend;

import org.lwjgl.vulkan.VkQueue;

/** Raw queue handle and the family index required when creating command pools and shared resources. */
public record VulkanQueueRef(VkQueue queue, int familyIndex) {
}
