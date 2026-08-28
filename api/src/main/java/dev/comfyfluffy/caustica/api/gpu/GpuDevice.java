package dev.comfyfluffy.caustica.api.gpu;

import org.lwjgl.vulkan.VkDevice;


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
 *
 * <p>The logical device is Vulkan 1.4. The renderer enables the features required for buffer device
 * addresses, dynamic rendering, synchronization2, unified {@code GENERAL} image layouts, descriptor
 * heaps, shader objects, untyped pointers, acceleration structures, ray-tracing pipelines, ray queries,
 * and ray-tracing position fetch. The corresponding device extensions are
 * {@code VK_KHR_unified_image_layouts}, {@code VK_EXT_descriptor_heap},
 * {@code VK_EXT_shader_object}, {@code VK_KHR_shader_untyped_pointers},
 * {@code VK_KHR_acceleration_structure}, {@code VK_KHR_deferred_host_operations},
 * {@code VK_KHR_ray_tracing_pipeline}, {@code VK_KHR_ray_query}, and
 * {@code VK_KHR_ray_tracing_position_fetch}.
 *
 * <p>Extensions query implementation-dependent limits and physical-device support directly through
 * {@link VkDevice#getPhysicalDevice()} and Vulkan's extensible feature/property queries. A reported
 * physical feature is not permission to use it: features outside the baseline above are not guaranteed to
 * have been enabled when the logical device was created. Optional device features require a separate
 * pre-device contract when a concrete extension use case needs one.
 */
public interface GpuDevice {
    /**
     * The live Vulkan device used to record and create pass-local Vulkan objects. Its physical device and
     * LWJGL command capabilities are available directly from the returned object.
     */
    VkDevice vk();

    /**
     * The renderer's VMA allocator, as a raw {@code VmaAllocator} handle. LWJGL represents VMA's opaque
     * allocator handle as {@code long}; it provides no typed wrapper class.
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
     * <p>This is the session service for retiring resources not tied to a recording currently in progress.
     * A resource can
     * become logically wrong — the display resized, host content changed, an object was dropped — and
     * that is never the same instant as when the GPU has finished reading it. Between those two instants
     * the resource must stay alive, and nothing an extension can observe tells it when the second one
     * arrives. This does.
     *
     * <p>The call is safe only after the caller has prevented the old resource from being captured by work
     * which is still recording or will record later. The usual place is the pass callback which publishes
     * the replacement before it records any current-frame use. A CPU worker prepares replacement state and
     * hands it to that pass; it must not swap a live GPU root and retire the old resource concurrently with
     * recording. Data borrowed through retained geometry, scenes, or program implementations uses that
     * owner's retirement callback instead.
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
     * <p>A session-created pass may destroy its exclusively owned resources directly from
     * {@link dev.comfyfluffy.caustica.api.pass.Pass#close() Pass.close()}: every GPU use by that pass has
     * drained then. This says nothing about
     * unrelated device work. Everywhere else, replacing or dropping a resource requires retirement.
     *
     * <p>Eligible callbacks run on the renderer thread in registration order and must not block or throw.
     * This covers work already submitted, not the frame currently being recorded — for that,
     * {@link GpuFrameUse#retire} is the tighter reservation. It does not imply that later device work or
     * unrelated passes are idle.
     *
     * <p>This method is thread-safe, but thread safety does not create the publication ordering described
     * above. If the session no longer accepts retirement, the call throws without taking {@code cleanup}.
     */
    void retireAfterUse(Runnable cleanup);
}
