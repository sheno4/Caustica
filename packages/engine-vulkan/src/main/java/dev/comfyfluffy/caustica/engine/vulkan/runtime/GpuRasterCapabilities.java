package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.lwjgl.vulkan.VK10;

/** Optional raster features and limits used by renderer-owned overlay pipelines. */
public record GpuRasterCapabilities(boolean wideLines, float maxLineWidth, int preferredColorSampleCount) {
    public GpuRasterCapabilities {
        if (!Float.isFinite(maxLineWidth) || maxLineWidth < 1.0f) {
            throw new IllegalArgumentException("maxLineWidth must be finite and at least 1.0");
        }
        if (preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_1_BIT
                && preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_2_BIT
                && preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_4_BIT
                && preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_8_BIT
                && preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_16_BIT
                && preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_32_BIT
                && preferredColorSampleCount != VK10.VK_SAMPLE_COUNT_64_BIT) {
            throw new IllegalArgumentException("preferredColorSampleCount must be one Vulkan sample-count bit");
        }
    }
}
