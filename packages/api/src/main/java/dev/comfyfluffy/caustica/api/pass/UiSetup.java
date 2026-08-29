package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;

import java.util.Objects;

/**
 * Fixed format needed to create a UI pipeline for one render session.
 *
 * @param gpu session GPU services
 * @param layerFormat UI-layer VkFormat
 */
public record UiSetup(GpuDevice gpu, int layerFormat) implements PassSetup {
    public UiSetup {
        Objects.requireNonNull(gpu, "gpu");
    }
}
