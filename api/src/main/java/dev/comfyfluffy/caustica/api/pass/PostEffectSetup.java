package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;

import java.util.Objects;

/**
 * Fixed formats needed to create a post-effect pipeline for one render session.
 *
 * @param gpu session GPU services
 * @param sceneColorFormat scene colour/output VkFormat
 * @param exposureFormat exposure-image VkFormat
 */
public record PostEffectSetup(GpuDevice gpu, int sceneColorFormat, int exposureFormat) implements PassSetup {
    public PostEffectSetup {
        Objects.requireNonNull(gpu, "gpu");
    }
}
