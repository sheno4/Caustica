/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

import dev.comfyfluffy.cloudly.vulkan.SharedImage;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/** Descriptor views borrowing a host image; close after the frame completes, before the host image dies. */
public final class BorrowedGpuImage implements GpuImage, AutoCloseable {
    private final long image, view;
    private final int width, height;
    private final int format;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
    public BorrowedGpuImage(GpuDevice gpu, SharedImage image, int format) {
        this(gpu, image.image(), image.view(), image.width(), image.height(), format);
    }
    public BorrowedGpuImage(GpuDevice gpu, long image, long view, int width, int height, int format) {
        if (image == 0 || view == 0 || width < 1 || height < 1) {
            throw new IllegalArgumentException("A live image, view and positive extent are required");
        }
        this.image = image;
        this.view = view;
        this.width = width;
        this.height = height;
        this.format = format;
        descriptors = gpu.descriptorHeap().allocateResources(2);
        gpu.descriptorHeap().writeBorrowedImage(descriptors, 0, GpuImageDescriptorKind.SAMPLED, view, VK_IMAGE_LAYOUT_GENERAL);
        gpu.descriptorHeap().writeBorrowedImage(descriptors, 1, GpuImageDescriptorKind.STORAGE, view, VK_IMAGE_LAYOUT_GENERAL);
    }
    public long image() { return image; }
    public long view() { return view; }
    public int width() { return width; }
    public int height() { return height; }
    public int format() { return format; }
    public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
        var index = new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + (kind == GpuImageDescriptorKind.STORAGE ? 1 : 0));
        return new GpuImageDescriptor() {
            public GpuImageDescriptorKind kind() { return kind; }
            public GpuDescriptorIndex.Resource index() { return index; }
        };
    }
    @Override public void close() { descriptors.destroy(); }
}
