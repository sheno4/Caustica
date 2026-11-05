package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Frame constants are reused only after the frame's last GPU ownership claim has retired. */
public final class CloudlyFrameArena implements AutoCloseable {
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final Map<Integer, ArrayDeque<VmaMappedBuffer>> available = new HashMap<>();
    private boolean closed;

    public CloudlyFrameArena(GpuDevice gpu, ResourceFactory resources) {
        this.gpu = gpu;
        this.resources = resources;
    }

    /** The caller writes and flushes this producer claim, retains it for the frame, then closes it. */
    public synchronized Upload acquire(int byteSize) {
        if (closed) throw new IllegalStateException("Cloudly frame arena is closed");
        int capacity = Math.multiplyExact(Math.floorDiv(Math.addExact(byteSize, 255), 256), 256);
        ArrayDeque<VmaMappedBuffer> buffers = available.computeIfAbsent(capacity, ignored -> new ArrayDeque<>());
        VmaMappedBuffer buffer = buffers.pollFirst();
        if (buffer == null) buffer = VmaMappedBuffer.create(gpu, capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "Cloudly original frame constants");
        VmaMappedBuffer allocated = buffer;
        try {
            ResourceOwner owner = resources.create(() -> recycle(capacity, allocated));
            return new Upload(allocated, byteSize, owner);
        } catch (RuntimeException | Error failure) { allocated.close(); throw failure; }
    }

    private synchronized void recycle(int capacity, VmaMappedBuffer buffer) {
        if (closed) buffer.close();
        else available.get(capacity).addLast(buffer);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        var idle = available.values().stream().flatMap(ArrayDeque::stream).toList();
        available.clear();
        new ResourceLifetime(idle.stream().<Runnable>map(buffer -> buffer::close).toArray(Runnable[]::new)).close();
    }

    public record Upload(VmaMappedBuffer buffer, int byteSize, ResourceOwner owner) implements AutoCloseable {
        public ByteBuffer bytes() { return buffer.mapped().slice(0, byteSize).order(ByteOrder.LITTLE_ENDIAN); }
        public long addressAt(int byteOffset) { return buffer.deviceAddressAt(byteOffset).value(); }
        public void flush() { buffer.flush(0, byteSize); }
        @Override public void close() { owner.close(); }
    }
}
