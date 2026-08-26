package dev.comfyfluffy.caustica.api.gpu;

/**
 * An extension-owned Vulkan image and image view allocated through the renderer's device service.
 *
 * <p>Images created through {@link GpuDevice} use {@code VK_IMAGE_LAYOUT_GENERAL}. The creating pass owns
 * the image and frees it through {@link GpuDevice#retireAfterUse}, not by calling {@link #destroy()}
 * directly — the only place a direct destroy is safe is a lifecycle's final callback, where the device is
 * already idle. Images borrowed from a frame context remain renderer-owned and must not be destroyed.
 */
public interface GpuImage {
    long image();
    long view();
    int width();
    int height();
    int format();
    void destroy();
}
