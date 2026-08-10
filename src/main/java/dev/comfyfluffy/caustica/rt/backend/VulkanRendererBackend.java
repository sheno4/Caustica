package dev.comfyfluffy.caustica.rt.backend;

import org.lwjgl.vulkan.VkDevice;

/** Host services required by the renderer's Vulkan orchestration. */
public interface VulkanRendererBackend {
    VkDevice device();

    VulkanQueueRef graphicsQueue();

    VulkanQueueRef computeQueue();

    GraphicsSubmission createGraphicsSubmission();

    void assertRenderThread();

    DebugMarkers debugMarkers();

    boolean rayTracingProvisioned();
}
