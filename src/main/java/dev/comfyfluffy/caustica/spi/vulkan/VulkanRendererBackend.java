package dev.comfyfluffy.caustica.spi.vulkan;

import org.lwjgl.vulkan.VkDevice;

/** Vulkan host services required by the renderer's device orchestration. */
public interface VulkanRendererBackend {
    VkDevice device();

    VulkanQueueRef graphicsQueue();

    VulkanQueueRef computeQueue();

    GraphicsSubmission createGraphicsSubmission();

    void assertRenderThread();

    DebugMarkers debugMarkers();

    boolean rayTracingProvisioned();
}
