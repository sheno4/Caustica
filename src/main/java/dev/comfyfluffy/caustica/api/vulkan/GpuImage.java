package dev.comfyfluffy.caustica.api.vulkan;

/**
 * Native image, view, dimensions and shader descriptors exposed without destruction authority.
 *
 * <p>Images obtained from a pass frame are borrowed for that callback. Resolve them again each frame;
 * resizing can replace the image. The renderer keeps recorded uses alive through GPU completion.
 * Copying an image reference or descriptor index does not extend the borrow.
 *
 * <p>{@link OwnedGpuImage} adds an independent ownership claim for images supplied by a host. Retaining
 * that claim keeps the image, view and descriptors alive across frames; this base interface alone
 * grants no ownership. Extensions may also allocate and own native images through
 * {@link GpuDevice#vmaAllocator()}.
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
     * Shader descriptor with the requested image interpretation. The returned view follows this image's
     * borrow or ownership claim; it cannot be retired independently. Read-only versus read-write storage
     * access is a shader declaration and synchronization property, not a different Vulkan descriptor.
     *
     * @throws IllegalArgumentException if this image was not created for the requested interpretation
     */
    GpuImageDescriptor descriptor(GpuImageDescriptorKind kind);

    int width();

    int height();

    int format();
}
