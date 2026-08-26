package dev.comfyfluffy.caustica.api.gpu;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

/**
 * Vulkan device services available to extensions.
 *
 * <p>The renderer owns the device, the allocator, and the descriptor heaps. Everything an extension
 * allocates through them is its own, and it frees them through {@link #retireAfterUse} rather than
 * destroying them outright — see that method for why that is the only safe form anywhere except a
 * lifecycle's final callback.
 *
 * <p>Device discovery, queues, submission, and renderer lifecycle are intentionally outside this API. GPU
 * work is recorded by a pass; an extension may prepare it on its own CPU executors first.
 */
public interface GpuDevice {
    /** The live Vulkan device used to record and create pass-local Vulkan objects. */
    VkDevice vk();

    /**
     * The renderer's VMA allocator, as a raw {@code VmaAllocator} handle.
     *
     * <p><b>This is how an extension allocates.</b> There are no buffer or image factories here: the
     * renderer has no opinion to enforce about an extension's own memory — it never reads it, never binds
     * it, and under the descriptor heap never learns it exists — so a factory would only have been a helper,
     * and helpers are not in this artifact.
     *
     * <p>It is deliberately the same allocator the renderer uses rather than a separate one, so extension
     * allocations land in the same budget and the same fragmentation picture. Allocating through
     * {@code vkAllocateMemory} directly would leave memory the renderer cannot account for.
     *
     * <p>VMA is internally synchronized, so calls are safe from any thread. Whatever is allocated through
     * it is the caller's to free, and must be freed before the device goes away.
     */
    long vmaAllocator();

    /** The renderer-owned resource and sampler heaps shared by every pipeline and pass. */
    GpuDescriptorHeap descriptorHeap();

    /**
     * Run {@code cleanup} once every command submitted before this call has completed on the GPU.
     *
     * <p><b>This is the API's only resource-lifetime primitive.</b> Epoch callbacks say a resource has
     * become logically wrong — the display resized, the resource pack changed, the mesh was dropped — and
     * that is never the same instant as when the GPU has finished reading it. Between those two instants
     * the resource must stay alive, and nothing an extension can observe tells it when the second one
     * arrives. This does.
     *
     * <p>So the shape of replacing a resource is always: allocate the new one, hand the old one to this,
     * keep going.
     *
     * <pre>
     *   public void record(PassFrame frame) {
     *       if (frame.renderWidth() == builtWidth) return;
     *       long previousImage = image;
     *       long previousAllocation = allocation;
     *       allocate(frame.renderWidth(), frame.renderHeight());
     *       if (previousImage != 0L) gpu.retireAfterUse(
     *               () -&gt; Vma.vmaDestroyImage(gpu.vmaAllocator(), previousImage, previousAllocation));
     *   }
     * </pre>
     *
     * <p>Destroying directly is correct in exactly one place: a lifecycle's final callback
     * ({@code PassLifecycle.destroy}, {@code ProviderLifecycle.shutdown}), where the renderer has already
     * idled the device. Everywhere else it is a use-after-free that survives testing, because the
     * window is one frame wide.
     *
     * <p>Cleanup runs on the renderer thread and must not throw. This covers work already submitted, not
     * the frame currently being recorded — for that, {@link GpuFrameUse#retire} is the tighter reservation.
     */
    void retireAfterUse(Runnable cleanup);
}
