package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;

/** Temporal image upscaler selected independently of the renderer's denoising backend. */
public interface RtUpscaler {
    record Extent(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        public Extent {
            if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
                throw new IllegalArgumentException("upscaler extents must be positive");
            }
        }
    }

    /** The command buffer is dedicated to conventional descriptor bindings for this evaluation. */
    record Frame(VkCommandBuffer commandBuffer, GpuImage color, GpuImage depth, GpuImage motion,
                 GpuImage output, Extent extent, float jitterX, float jitterY,
                 boolean reset, float preExposure) {
        public Frame {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(depth, "depth");
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(extent, "extent");
            if (!(preExposure > 0.0f) || !Float.isFinite(preExposure)) {
                throw new IllegalArgumentException("preExposure must be finite and positive");
            }
        }
    }

    boolean configured();

    int configurationKey();

    int[] queryOptimalRenderSize(int displayWidth, int displayHeight);

    boolean record(Frame frame);

    void resetHistory();

    void destroyAfterDeviceIdle();
}
