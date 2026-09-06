package dev.comfyfluffy.caustica.api.vulkan;

import org.lwjgl.vulkan.VkDevice;


/**
 * Vulkan device services available to extensions.
 *
 * <p>The renderer owns the device, allocator, and descriptor heaps. Extensions own their allocations and
 * keep shared resource owners alive through every frame or job that uses their allocations.
 * Final owner release runs the allocation cleanup callback.
 *
 * <p>Extensions submit asynchronous initialization through their contribution's
 * {@link GpuComputeQueue}, record frame-dependent work through passes, and may prepare either on their own
 * CPU executors. This API does not expose device discovery, queues, submission, or renderer lifecycle.
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

    /**
     * Queue-family indices used by buffers accessed from both graphics and renderer async compute.
     * The returned array contains each family exactly once.
     */
    int[] asyncBufferSharingQueueFamilies();

    /** The renderer-owned resource and sampler heaps shared by every pipeline and pass. */
    GpuDescriptorHeap descriptorHeap();

}
