package dev.comfyfluffy.caustica.minecraft.overlay;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.joml.Matrix4fc;

import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;

/**
 * One world-space overlay effect rendered by {@link WorldOverlayPass} into the display-resolution UI
 * layer. Implementations create their pipelines through
 * {@link OverlayPipelines} (reusing an existing vertex-format/blend combination where one fits) and take
 * per-frame vertex scratch from the shared {@link OverlayFramePool} — never own one-off pools.
 */
public interface OverlayFeature {
    /**
     * Gather this frame's CPU-side data, lazily create GPU resources, and upload vertex scratch via
     * {@code pool}. Runs before any command recording; return false to skip {@link #record} this frame.
     * {@code graphicsUse} is the exact completion token for resources referenced by the recorded commands.
     * {@code width}/{@code height} are the composite target's (display-res) extent.
     */
    boolean prepare(GpuDevice device, OverlayFramePool pool, GpuFrameUse gpuUse,
                    int worldTlasDescriptor, Matrix4fc worldViewProjection, int width, int height);

    /**
     * Record this feature's passes. {@code targetView} is the renderer-owned premultiplied-sRGB UI layer in
     * {@code GENERAL} layout. Composite onto it with {@code loadOp = LOAD} and the straight-alpha over blend.
     * Host vertex writes are already visible; barriers between a feature's own passes are the feature's
     * responsibility, and {@link WorldOverlayPass} inserts barriers between features.
     */
    void record(VkCommandBuffer cmd, long targetView, int width, int height);

    /** Destroy GPU resources (device is idle); must tolerate never-prepared and repeated calls. */
    void close();
}
