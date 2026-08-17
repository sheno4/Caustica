package dev.comfyfluffy.caustica.api.gpu;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Vulkan device services available to registered render passes.
 *
 * <p>The renderer owns the device and allocator. A pass owns every buffer and image it creates through
 * this interface and must destroy those resources when the pass is destroyed or replaces them on resize.
 * Device discovery, queues, submission, and renderer lifecycle are intentionally outside this API.
 */
public interface GpuDevice {
    /** The live Vulkan device used to record and create pass-local Vulkan objects. */
    VkDevice vk();

    /** Create a buffer with a device address. Host-visible buffers are persistently mapped. */
    GpuBuffer createBuffer(long size, int usage, boolean hostVisible, String label);

    /** Create a sampled storage image in {@code VK_IMAGE_LAYOUT_GENERAL}. */
    GpuImage createStorageImage(int width, int height, int format, String label);

    /**
     * Create a sampled storage image in {@code VK_IMAGE_LAYOUT_GENERAL}, adding caller-supplied Vulkan
     * usage flags to the standard storage, sampled, and transfer usages.
     */
    GpuImage createStorageImage(int width, int height, int format, String label, int extraUsage);

    /** Create a transient multisampled color attachment that resolves into a single-sample image. */
    GpuImage createTransientMsaaColorImage(int width, int height, int format, int samples, String label);

    /** Assign a diagnostic label to a Vulkan object when host debug markers are available. */
    void nameObject(int objectType, long handle, String label);

    /** Begin a diagnostic label around commands recorded by a pass. */
    GpuDebugScope debugScope(VkCommandBuffer commandBuffer, String label);
}
