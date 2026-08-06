package dev.comfyfluffy.caustica.rt.accel;

import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

import java.util.Objects;

/**
 * A VMA-backed image + view, created in {@code VK_IMAGE_LAYOUT_GENERAL}. Used for RT output
 * storage images and render-pass resources. Created via
 * {@link dev.comfyfluffy.caustica.rt.RtContext#createStorageImage}; freed with {@link #destroy()}.
 */
public final class GpuImage {
    public final long image;
    public final long allocation;
    public final long view;
    public final int width;
    public final int height;
    public final int format;
    public final int mipLevels;
    /** Usage flags passed to image creation, including any caller-supplied extra bits. */
    public final int usage;
    public final String label;

    private final long vma;
    private final VkDevice vk;
    private boolean destroyed;

    public GpuImage(long vma, VkDevice vk, long image, long allocation, long view, int width, int height,
                    int format, int mipLevels, int usage, String label) {
        this.vma = vma;
        this.vk = vk;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
        this.format = format;
        this.mipLevels = mipLevels;
        this.usage = usage;
        this.label = Objects.requireNonNull(label, "label");
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    /** Throws once this image has been destroyed; call before touching {@link #image}/{@link #view}. */
    public void requireNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException(label + " was already destroyed");
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (view != 0L) {
            VK10.vkDestroyImageView(vk, view, null);
        }
        if (image != 0L) {
            Vma.vmaDestroyImage(vma, image, allocation);
        }
        destroyed = true;
    }
}
