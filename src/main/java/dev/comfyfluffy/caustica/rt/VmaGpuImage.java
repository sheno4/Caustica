package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

import java.util.Objects;

final class VmaGpuImage implements GpuImage {
    private final long vma;
    private final VkDevice vk;
    private final long image;
    private final long allocation;
    private final long view;
    private final int width;
    private final int height;
    private final int format;
    private final int mipLevels;
    private final int usage;
    private final String label;
    private boolean destroyed;

    VmaGpuImage(long vma, VkDevice vk, long image, long allocation, long view, int width, int height,
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

    @Override public long image() { return image; }
    @Override public long view() { return view; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int format() { return format; }
    @Override public int mipLevels() { return mipLevels; }
    @Override public int usage() { return usage; }
    @Override public String label() { return label; }
    @Override public boolean isDestroyed() { return destroyed; }

    @Override
    public void requireNotDestroyed() {
        if (destroyed) throw new IllegalStateException(label + " was already destroyed");
    }

    @Override
    public void destroy() {
        if (destroyed) return;
        if (view != 0L) VK10.vkDestroyImageView(vk, view, null);
        if (image != 0L) Vma.vmaDestroyImage(vma, image, allocation);
        destroyed = true;
    }
}
