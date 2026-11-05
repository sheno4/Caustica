package dev.comfyfluffy.caustica.api.vulkan;

import dev.comfyfluffy.caustica.rt.accel.RtImage;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/** Descriptor views borrowing a host image; close after the frame completes, before the host image dies. */
public final class BorrowedGpuImage implements GpuImage, AutoCloseable {
    private final RtImage image;
    private final int format;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
    public BorrowedGpuImage(GpuDevice gpu, RtImage image, int format) {
        this.image = image;
        this.format = format;
        descriptors = gpu.descriptorHeap().allocateResources(2);
        gpu.descriptorHeap().writeBorrowedImage(descriptors, 0, GpuImageDescriptorKind.SAMPLED, image.view, VK_IMAGE_LAYOUT_GENERAL);
        gpu.descriptorHeap().writeBorrowedImage(descriptors, 1, GpuImageDescriptorKind.STORAGE, image.view, VK_IMAGE_LAYOUT_GENERAL);
    }
    public long image() { return image.image; }
    public long view() { return image.view; }
    public int width() { return image.width; }
    public int height() { return image.height; }
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
