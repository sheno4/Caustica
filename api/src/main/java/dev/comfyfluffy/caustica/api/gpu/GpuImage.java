package dev.comfyfluffy.caustica.api.gpu;

/**
 * A renderer-owned image lent to a pass for the frame it is recording — the scene colour and its chain
 * target, the exposure image, the UI layer.
 *
 * <p><b>Never extension-owned, so there is nothing here to destroy.</b> An extension allocates its own
 * images through {@link GpuDevice#vmaAllocator()} and holds the raw handles, which is also how it frees
 * them; this type exists only for what the renderer hands out. The two used to be one type with opposite
 * ownership rules told apart by prose, and a {@code destroy()} that was required on one and forbidden on
 * the other.
 *
 * <p>Resolved fresh every frame. Never cache one across frames — a resize recreates it.
 */
public interface GpuImage {
    long image();

    long view();

    int width();

    int height();

    int format();
}
