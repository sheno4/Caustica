package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** A mapped host RT allocation registered for ordinary buffer and texel-buffer descriptors. */
public final class VmaMappedBuffer implements AutoCloseable {
    private final GpuDevice gpu;
    private final RtBuffer buffer;
    private boolean closed;
    private VmaMappedBuffer(GpuDevice gpu, RtBuffer buffer) {
        this.gpu = gpu;
        this.buffer = buffer;
        gpu.descriptorHeap().registerBuffer(buffer.deviceAddress, buffer.handle, buffer.size);
    }
    public static VmaMappedBuffer create(GpuDevice gpu, long bytes, int usage, String label) {
        return create(gpu, bytes, usage, 0L, label);
    }
    public static VmaMappedBuffer create(GpuDevice gpu, long bytes, int usage, long alignment, String label) {
        return new VmaMappedBuffer(gpu, gpu.context().createAlignedBuffer(bytes, usage, true, label, alignment));
    }
    public static VmaMappedBuffer createAsync(GpuDevice gpu, long bytes, int usage, String label) {
        return new VmaMappedBuffer(gpu, gpu.context().createAsyncBuffer(bytes, usage, true, label));
    }
    public long buffer() { return buffer.handle; }
    public long byteSize() { return buffer.size; }
    public VulkanDeviceAddressRange deviceRange() {
        return new VulkanDeviceAddressRange(new VulkanDeviceAddress(buffer.deviceAddress), buffer.size);
    }
    public VulkanDeviceAddress deviceAddressAt(long offset) {
        if (offset < 0 || offset >= buffer.size) throw new IndexOutOfBoundsException("Buffer device-address offset");
        return new VulkanDeviceAddress(buffer.deviceAddress + offset);
    }
    public ByteBuffer mapped() { return MemoryUtil.memByteBuffer(buffer.mapped, Math.toIntExact(buffer.size)); }
    public void flush(long offset, long bytes) { buffer.flush(offset, bytes); }
    public void invalidate(long offset, long bytes) { buffer.invalidate(offset, bytes); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        gpu.descriptorHeap().unregisterBuffer(buffer.deviceAddress, buffer.handle);
        buffer.destroy();
    }
}
