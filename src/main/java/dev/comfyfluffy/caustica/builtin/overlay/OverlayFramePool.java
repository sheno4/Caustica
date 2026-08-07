package dev.comfyfluffy.caustica.builtin.overlay;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;

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
    public GpuBuffer acquireVertex(RtContext ctx, long bytes, String label) {
        return acquire(ctx, bytes, VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, label);
    }

    /** A host-visible index buffer of at least {@code bytes}, valid for this frame only. */
    public GpuBuffer acquireIndex(RtContext ctx, long bytes, String label) {
        return acquire(ctx, bytes, VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT, label);
    }

    private GpuBuffer acquire(RtContext ctx, long bytes, int usage, String label) {
        GpuBuffer b = ctx.createBuffer(Math.max(bytes, MIN_SIZE), usage, true, label);
        acquiredThisFrame.add(b);
        return b;
    }

    /** Retire everything acquired this frame once its overlay commands have completed. */
    public void endFrame(RtContext ctx, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (acquiredThisFrame.isEmpty()) {
            return;
        }
        List<GpuBuffer> retired = List.copyOf(acquiredThisFrame);
        ctx.gpuExecutor().retireAfterGraphics(graphicsUse, () -> retired.forEach(GpuBuffer::destroy));
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
