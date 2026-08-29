package dev.comfyfluffy.caustica.minecraft.overlay;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Frame-scoped host-visible vertex and index buffers retired by the UI frame reservation. */
final class OverlayFramePool {
    private static final long MIN_SIZE = 256;
    private final List<Buffer> acquired = new ArrayList<>();

    Buffer acquireVertex(GpuDevice gpu, long bytes, String label) {
        return acquire(gpu, bytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, label);
    }
    Buffer acquireIndex(GpuDevice gpu, long bytes, String label) {
        return acquire(gpu, bytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, label);
    }
    private Buffer acquire(GpuDevice gpu, long bytes, int usage, String label) {
        Buffer buffer = Buffer.create(gpu, Math.max(bytes, MIN_SIZE), usage, label);
        acquired.add(buffer);
        return buffer;
    }
    void endFrame(GpuFrameUse use) {
        if (acquired.isEmpty()) return;
        List<Buffer> retired = List.copyOf(acquired);
        acquired.clear();
        use.whenComplete(() -> retired.forEach(Buffer::close));
    }
    void close() { acquired.forEach(Buffer::close); acquired.clear(); }

    static final class Buffer implements AutoCloseable {
        private final VmaMappedHostBuffer allocation;

        private Buffer(VmaMappedHostBuffer allocation) {
            this.allocation = allocation;
        }

        static Buffer create(GpuDevice gpu, long size, int usage, String label) {
            return new Buffer(VmaMappedHostBuffer.create(gpu, size, usage, label));
        }

        long handle() { return allocation.buffer(); }
        ByteBuffer mapped() { return allocation.mapped(); }

        void flush(long offset, long bytes) {
            allocation.flush(offset, bytes);
        }

        @Override public void close() {
            allocation.close();
        }
    }
}
