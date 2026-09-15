package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Event;
import jdk.jfr.Enabled;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.lwjgl.util.vma.VmaAllocateDeviceMemoryFunction;
import org.lwjgl.util.vma.VmaDeviceMemoryCallbacks;
import org.lwjgl.util.vma.VmaFreeDeviceMemoryFunction;

/** Reports renderer VMA device-memory blocks, not individual buffer suballocations. */
final class VmaMemoryTelemetry implements AutoCloseable {
    private static final EventType EVENT = EventType.getEventType(GpuMemoryBlock.class);
    private final VmaAllocateDeviceMemoryFunction allocate = VmaAllocateDeviceMemoryFunction.create(
            (allocator, type, memory, size, user) -> record(type, memory, size, true));
    private final VmaFreeDeviceMemoryFunction free = VmaFreeDeviceMemoryFunction.create(
            (allocator, type, memory, size, user) -> record(type, memory, size, false));
    private final VmaDeviceMemoryCallbacks callbacks = VmaDeviceMemoryCallbacks.calloc()
            .pfnAllocate(allocate).pfnFree(free);

    VmaDeviceMemoryCallbacks callbacks() { return callbacks; }

    private static void record(int type, long memory, long size, boolean allocated) {
        if (!EVENT.isEnabled()) return;
        GpuMemoryBlock event = new GpuMemoryBlock();
        event.memoryType = type;
        event.memory = memory;
        event.bytes = size;
        event.allocated = allocated;
        event.commit();
    }

    /** The allocator must be destroyed before its native callbacks are released. */
    @Override public void close() {
        callbacks.free();
        free.free();
        allocate.free();
    }

    @Name("dev.comfyfluffy.caustica.GpuMemoryBlock")
    @Label("Renderer VMA device-memory block")
    @Category("Caustica")
    @Enabled(false)
    static final class GpuMemoryBlock extends Event {
        int memoryType;
        long memory;
        @DataAmount(DataAmount.BYTES) long bytes;
        boolean allocated;
    }
}
