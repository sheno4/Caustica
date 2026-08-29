package dev.comfyfluffy.caustica.minecraft.overlay;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
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
        use.retire(() -> retired.forEach(Buffer::close));
    }
    void close() { acquired.forEach(Buffer::close); acquired.clear(); }

    static final class Buffer implements AutoCloseable {
        private final long allocator, handle, allocation, mapped, size;
        private boolean closed;
        private Buffer(long allocator, long handle, long allocation, long mapped, long size) {
            this.allocator = allocator; this.handle = handle; this.allocation = allocation;
            this.mapped = mapped; this.size = size;
        }
        static Buffer create(GpuDevice gpu, long size, int usage, String label) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                        .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer output = stack.mallocLong(1);
                PointerBuffer allocation = stack.mallocPointer(1);
                VmaAllocationInfo allocationOut = VmaAllocationInfo.calloc(stack);
                int result = Vma.vmaCreateBuffer(gpu.vmaAllocator(), info, allocationInfo,
                        output, allocation, allocationOut);
                if (result != VK_SUCCESS) throw new IllegalStateException(label + " allocation failed: " + result);
                return new Buffer(gpu.vmaAllocator(), output.get(0), allocation.get(0),
                        allocationOut.pMappedData(), size);
            }
        }
        long handle() { return handle; }
        long mapped() { return mapped; }
        void flush(long offset, long bytes) {
            if (offset < 0 || bytes < 0 || offset + bytes > size) throw new IllegalArgumentException("flush range");
            Vma.vmaFlushAllocation(allocator, allocation, offset, bytes);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            Vma.vmaDestroyBuffer(allocator, handle, allocation);
        }
    }
}
