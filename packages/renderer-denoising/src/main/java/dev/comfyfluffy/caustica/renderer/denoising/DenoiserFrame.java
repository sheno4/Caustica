package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/** One denoiser dispatch in a recording command buffer dedicated to conventional descriptor bindings. */
public record DenoiserFrame(long commandBuffer, DenoiserCommonSettings common, DenoiserInputs inputs) {
    public DenoiserFrame {
        if (commandBuffer == 0L) throw new IllegalArgumentException("commandBuffer must be a non-null Vulkan handle");
        Objects.requireNonNull(common, "common");
        Objects.requireNonNull(inputs, "inputs");
    }

    public DenoiserExtent extent() {
        return inputs.extent();
    }
}
