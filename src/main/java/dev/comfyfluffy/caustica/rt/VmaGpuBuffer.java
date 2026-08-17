package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import org.lwjgl.util.vma.Vma;

import java.util.Objects;

final class VmaGpuBuffer implements GpuBuffer {
    private final long vma;
    private final long handle;
    private final long allocation;
    private final long deviceAddress;
    private final long mapped;
    private final long size;
    private final int usage;
    private final boolean hostVisible;
    private final String label;
    private final Runnable beforeDestroy;
    private boolean destroyed;

    VmaGpuBuffer(long vma, long handle, long allocation, long deviceAddress, long mapped, long size, int usage,
                 boolean hostVisible, String label, Runnable beforeDestroy) {
        this.vma = vma;
        this.handle = handle;
        this.allocation = allocation;
        this.deviceAddress = deviceAddress;
        this.mapped = mapped;
        this.size = size;
        this.usage = usage;
        this.hostVisible = hostVisible;
        this.label = Objects.requireNonNull(label, "label");
        this.beforeDestroy = Objects.requireNonNull(beforeDestroy, "beforeDestroy");
    }

    @Override public long handle() { return handle; }
    @Override public long deviceAddress() { return deviceAddress; }
    @Override public long mapped() { return mapped; }
    @Override public long size() { return size; }
    @Override public int usage() { return usage; }
    @Override public boolean hostVisible() { return hostVisible; }
    @Override public boolean isDestroyed() { return destroyed; }

    @Override
    public void requireNotDestroyed() {
        if (destroyed) throw new IllegalStateException(label + " was already destroyed");
    }

    @Override
    public void destroy() {
        if (!destroyed && handle != 0L) {
            beforeDestroy.run();
            Vma.vmaDestroyBuffer(vma, handle, allocation);
            destroyed = true;
        }
    }

    @Override public void flush() { flush(0L, size); }

    @Override
    public void flush(long offset, long length) {
        requireMappedRange("Flush", offset, length);
        Vma.vmaFlushAllocation(vma, allocation, offset, length);
    }

    @Override public void invalidate() { invalidate(0L, size); }

    @Override
    public void invalidate(long offset, long length) {
        requireMappedRange("Invalidate", offset, length);
        Vma.vmaInvalidateAllocation(vma, allocation, offset, length);
    }

    private void requireMappedRange(String operation, long offset, long length) {
        if (!hostVisible) throw new IllegalStateException("Cannot access a non-host-visible buffer");
        if (offset < 0L || length < 0L || offset > size || length > size - offset) {
            throw new IndexOutOfBoundsException(operation + " range " + offset + ".." + (offset + length)
                    + " exceeds buffer size " + size);
        }
    }
}
