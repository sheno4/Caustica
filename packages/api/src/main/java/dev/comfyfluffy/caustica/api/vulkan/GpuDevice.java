package dev.comfyfluffy.caustica.api.vulkan;

import org.lwjgl.vulkan.VkDevice;


/**
 * Vulkan device services available to extensions.
 *
 * <p>The renderer owns the device, allocator, and descriptor heaps. Extensions own their allocations and
 * release resources with {@link #retireAfterUse}, except when a lifecycle callback guarantees all uses
 * have drained.
 *
 * <p>Extensions record GPU work through passes and may prepare it on their own CPU executors. This API does
 * not expose device discovery, queues, submission, or renderer lifecycle.
 *
 * <p>The logical device is Vulkan 1.4. The renderer enables the features required for buffer device
 * addresses, 16-bit integer and floating-point shader arithmetic, dynamic rendering, synchronization2,
 * unified {@code GENERAL} image layouts, descriptor
 * heaps, push descriptors, shader objects, untyped pointers, acceleration structures, ray-tracing pipelines,
 * ray queries, and ray-tracing position fetch. The corresponding device extensions are
 * {@code VK_KHR_unified_image_layouts}, {@code VK_EXT_descriptor_heap},
 * {@code VK_KHR_push_descriptor}, {@code VK_EXT_shader_object}, {@code VK_KHR_shader_untyped_pointers},
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
     * <p>Use this allocator for extension-owned buffers and images so their memory participates in the
     * renderer's budget and allocation strategy.
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
     * <p>Use this for session resources not tied to the frame currently being recorded. It keeps a replaced
     * or dropped resource alive until earlier submissions stop using it.
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
     * drained then. This does not cover unrelated device work. Everywhere else, replacing or dropping a
     * resource requires retirement.
     *
     * <p>Eligible callbacks run on the renderer thread in registration order and must not block or throw.
     * This covers work already submitted, not the frame currently being recorded — for that,
     * {@link GpuFrameUse#whenComplete} is the tighter reservation. It does not imply that later device work or
     * unrelated passes are idle.
     *
     * <p>This method is thread-safe, but thread safety does not create the publication ordering described
     * above. If the session no longer accepts retirement, the call throws without taking {@code cleanup}.
     */
    void retireAfterUse(Runnable cleanup);
}
