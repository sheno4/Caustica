package dev.comfyfluffy.caustica.api.vulkan;

/**
 * A renderer-owned image lent to a pass for the frame it is recording — the scene colour and its chain
 * target, the exposure image, the UI layer.
 *
 * <p><b>Never extension-owned, so there is nothing here to destroy.</b> An extension allocates its own
 * images through {@link GpuDevice#vmaAllocator()} and holds the raw handles, which is also how it frees
 * them; this type exists only for what the renderer hands out.
 *
 * <p>Resolved fresh every frame. Never cache one across frames — a resize recreates it.
 *
 * <p>The image is always in {@code VK_IMAGE_LAYOUT_GENERAL}. Unified image layouts make that the shared
 * contract for sampling, storage writes, copies, and attachments; passes synchronize accesses but never
 * transition a borrowed image or substitute a stage-specific layout in a descriptor.
 */
public interface GpuImage {
    /**
     * Raw {@code VkImage}. LWJGL represents this non-dispatchable Vulkan handle as {@code long}; unlike
     * {@link org.lwjgl.vulkan.VkDevice} and {@link org.lwjgl.vulkan.VkCommandBuffer}, it has no wrapper type.
     */
    long image();

    /** Raw {@code VkImageView}, represented by LWJGL as {@code long} for the same reason. */
    long view();

    /**
     * Engine-owned shader descriptor with the requested image interpretation. The returned view follows
     * this frame borrow; the extension does not allocate or retire it. Read-only versus read-write storage
     * access is a shader declaration and synchronization property, not a different Vulkan descriptor.
     *
     * @throws IllegalArgumentException if this image was not created for the requested interpretation
     */
    GpuImageDescriptor descriptor(GpuImageDescriptorKind kind);

    int width();

    int height();

    int format();
}
