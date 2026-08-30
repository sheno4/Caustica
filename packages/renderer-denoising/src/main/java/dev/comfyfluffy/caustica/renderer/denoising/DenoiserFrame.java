package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/** One denoiser dispatch recorded into an already recording Vulkan command buffer. */
public record DenoiserFrame(long commandBuffer, DenoiserCommonSettings common, DenoiserInputs inputs) {
    public DenoiserFrame {
        if (commandBuffer == 0L) throw new IllegalArgumentException("commandBuffer must be a non-null Vulkan handle");
        Objects.requireNonNull(common, "common");
        Objects.requireNonNull(inputs, "inputs");
        if (common.alternateDisocclusionMixAvailable() != inputs.disocclusionThresholdMix().isPresent()) {
            throw new IllegalArgumentException("disocclusion mix setting and image must agree");
        }
        if (common.validationEnabled() != inputs.validationOutput().isPresent()) {
            throw new IllegalArgumentException("validation setting and output image must agree");
        }
    }

    public DenoiserExtent extent() {
        return inputs.extent();
    }
}
