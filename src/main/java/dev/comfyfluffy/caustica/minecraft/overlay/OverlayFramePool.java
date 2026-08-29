package dev.comfyfluffy.caustica.minecraft.overlay;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;

import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import dev.comfyfluffy.caustica.rt.GpuBuffer;
import dev.comfyfluffy.caustica.rt.GpuContext;

/**
 * Per-frame host-visible vertex/index scratch for overlay passes, shared by every {@link OverlayFeature}.
 * Buffers acquired during a frame retire against that frame's exact graphics completion token, so a buffer
 * is never destroyed while the GPU can still read it.
 */
public final class OverlayFramePool {
    // Vulkan requires buffer size > 0; a few zero-length overlay draws could otherwise reach acquire() with
    // bytes == 0.
    private static final long MIN_SIZE = 256;

    private final List<GpuBuffer> acquiredThisFrame = new ArrayList<>();

    /** A host-visible vertex buffer of at least {@code bytes}, valid for this frame only. */
    public GpuBuffer acquireVertex(GpuContext device, long bytes, String label) {
        return acquire(device, bytes, VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, label);
    }

    /** A host-visible index buffer of at least {@code bytes}, valid for this frame only. */
    public GpuBuffer acquireIndex(GpuContext device, long bytes, String label) {
        return acquire(device, bytes, VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT, label);
    }

    private GpuBuffer acquire(GpuContext device, long bytes, int usage, String label) {
        GpuBuffer b = device.createBuffer(Math.max(bytes, MIN_SIZE), usage, true, label);
        acquiredThisFrame.add(b);
        return b;
    }

    /** Retire everything acquired this frame once its overlay commands have completed. */
    public void endFrame(GpuFrameUse gpuUse) {
        if (acquiredThisFrame.isEmpty()) {
            return;
        }
        List<GpuBuffer> retired = List.copyOf(acquiredThisFrame);
        gpuUse.retire(() -> retired.forEach(GpuBuffer::destroy));
        acquiredThisFrame.clear();
    }

    /** Immediate teardown of unpublished buffers; queued buffers are owned by the GPU executor. */
    public void destroy() {
        for (GpuBuffer b : acquiredThisFrame) {
            b.destroy();
        }
        acquiredThisFrame.clear();
    }
}
