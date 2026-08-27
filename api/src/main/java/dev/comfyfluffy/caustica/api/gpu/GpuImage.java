package dev.comfyfluffy.caustica.api.gpu;

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
    long image();

    long view();

    int width();

    int height();

    int format();
}
