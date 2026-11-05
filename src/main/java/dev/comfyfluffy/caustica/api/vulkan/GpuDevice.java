package dev.comfyfluffy.caustica.api.vulkan;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import org.lwjgl.vulkan.VkDevice;

/** Cloudly services backed by the host RT device and a local descriptor registry. */
public final class GpuDevice implements AutoCloseable {
    private final RtContext context;
    private final GpuDescriptorHeap descriptors;
    public GpuDevice(RtContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        descriptors = new GpuDescriptorHeap(this);
    }
    public RtContext context() { return context; }
    public VkDevice vk() { return context.vk(); }
    public long vmaAllocator() { return context.vma(); }
    public int[] asyncBufferSharingQueueFamilies() {
        int graphics = context.device().graphicsQueue().queueFamilyIndex();
        int compute = RtDeviceBringup.computeQueueFamilyIndex();
        return graphics == compute ? new int[] { graphics } : new int[] { graphics, compute };
    }
    public GpuDescriptorHeap descriptorHeap() { return descriptors; }
    @Override public void close() { descriptors.close(); }
}
