package dev.comfyfluffy.caustica.api.gpu;

/**
 * A renderer-allocated Vulkan image and image view.
 *
 * <p>Images created through {@link GpuDevice} use {@code VK_IMAGE_LAYOUT_GENERAL}. The creating pass
 * owns the image and must call {@link #destroy()} once no recorded or submitted work can reference it.
 * Images borrowed from a frame context remain renderer-owned and must not be destroyed by the pass.
 */
public interface GpuImage {
    long image();
    long view();
    int width();
    int height();
    int format();
    void destroy();
}
