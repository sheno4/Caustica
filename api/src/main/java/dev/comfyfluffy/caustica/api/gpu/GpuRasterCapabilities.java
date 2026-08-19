package dev.comfyfluffy.caustica.api.gpu;

/** Rasterization limits exposed to render passes for device-compatible pipeline creation.
 * {@code preferredColorSampleCount} is a Vulkan {@code VkSampleCountFlagBits} value. */
public record GpuRasterCapabilities(boolean wideLines, float maxLineWidth, int preferredColorSampleCount) {
    public GpuRasterCapabilities {
        if (!Float.isFinite(maxLineWidth) || maxLineWidth < 1.0f) {
            throw new IllegalArgumentException("maxLineWidth must be finite and at least 1.0");
        }
        if (preferredColorSampleCount <= 0 || preferredColorSampleCount > 64
                || Integer.bitCount(preferredColorSampleCount) != 1) {
            throw new IllegalArgumentException("preferredColorSampleCount must be a single supported sample-count bit");
        }
    }
}
