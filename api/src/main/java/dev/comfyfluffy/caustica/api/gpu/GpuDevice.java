package dev.comfyfluffy.caustica.api.gpu;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

/**
 * Vulkan device services available to extensions.
 *
 * <p>The renderer owns the device and allocator. The caller owns every buffer and image it creates through
 * this interface, and frees them through {@link #retireAfterUse} rather than destroying them outright —
 * see that method for why that is the only safe form anywhere except a lifecycle's final callback.
 * Device discovery, queues, submission, and renderer lifecycle are intentionally outside this API.
 */
public interface GpuDevice {
    /** Generic device limits useful to pass-local raster pipelines. */
    GpuRasterCapabilities rasterCapabilities();

    /** The live Vulkan device used to record and create pass-local Vulkan objects. */
    VkDevice vk();

    /**
     * The renderer's VMA allocator, as a raw {@code VmaAllocator} handle.
     *
     * <p>The factories on this interface are the paved path: they exist where the renderer has an opinion
     * worth enforcing — a layout, a usage set, an initializing barrier. This is the escape hatch for
     * everything else, and it is deliberately the same allocator the renderer uses rather than a separate
     * one, so extension allocations land in the same budget and the same fragmentation picture. Allocating
     * through {@code vkAllocateMemory} directly would leave memory the renderer cannot account for.
     *
     * <p>VMA is internally synchronized, so calls are safe from any thread. Whatever is allocated through
     * it is the caller's to free, and must be freed before the device goes away.
     */
    long vmaAllocator();

    /** Create a buffer with a device address. Host-visible buffers are persistently mapped. */
    GpuBuffer createBuffer(long size, int usage, boolean hostVisible, String label);

    /**
     * Create a sampled storage image in {@code VK_IMAGE_LAYOUT_GENERAL}. {@code extraUsage} adds Vulkan
     * usage flags to the standard storage, sampled, and transfer usages; pass 0 for none.
     */
    GpuImage createStorageImage(int width, int height, int format, String label, int extraUsage);

    /**
     * Record and submit one-off work outside any frame, blocking until it completes. This is how an
     * extension does GPU work at a point where no frame is being recorded — uploading a texture at a
     * a resource rebuild, building a lookup table once at startup.
     */
    void submitImmediate(Consumer<VkCommandBuffer> record);

    /**
     * Run {@code cleanup} once every command submitted before this call has completed on the GPU.
     *
     * <p><b>This is the API's only resource-lifetime primitive.</b> Epoch callbacks say a resource has
     * become logically wrong — the display resized, the resource pack changed, the mesh was dropped — and
     * that is never the same instant as when the GPU has finished reading it. Between those two instants
     * the resource must stay alive, and nothing an extension can observe tells it when the second one
     * arrives. This does.
     *
     * <p>So the shape of every epoch callback that replaces a resource is: allocate the new one, hand the
     * old one to this, keep going.
     *
     * <pre>
     *   public void displayResized(PassSetup setup) {
     *       GpuImage previous = target;
     *       target = setup.device().createStorageImage(...);
     *       if (previous != null) setup.device().retireAfterUse(previous::destroy);
     *   }
     * </pre>
     *
     * <p>Calling {@code destroy()} directly is correct in exactly one place: a lifecycle's final callback
     * ({@code PassLifecycle.deactivated}, {@code ProviderLifecycle.shutdown}), where the renderer has
     * already idled the device. Everywhere else it is a use-after-free that survives testing, because the
     * window is one frame wide.
     *
     * <p>Cleanup runs on the renderer thread and must not throw. This covers work already submitted, not
     * the frame currently being recorded — for that, {@link GpuFrameUse#retire} is the tighter reservation.
     */
    void retireAfterUse(Runnable cleanup);

    /** Create a transient multisampled color attachment that resolves into a single-sample image. */
    GpuImage createTransientMsaaColorImage(int width, int height, int format, int samples, String label);

    /** Assign a diagnostic label to a Vulkan object when host debug markers are available. */
    void nameObject(int objectType, long handle, String label);

    /** Begin a diagnostic label around commands recorded by a pass. */
    GpuDebugScope debugScope(VkCommandBuffer commandBuffer, String label);
}
