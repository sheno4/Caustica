package dev.comfyfluffy.caustica.rt.pipeline;

import java.util.Objects;

/** Runtime-compiled SPIR-V for one world stage and its Vulkan debug name. */
public record RtShaderCode(String debugName, byte[] spirv) {
    public RtShaderCode {
        Objects.requireNonNull(debugName, "debugName");
        Objects.requireNonNull(spirv, "spirv");
        if (spirv.length == 0) {
            throw new IllegalArgumentException("empty SPIR-V for " + debugName);
        }
    }

    public static RtShaderCode of(String debugName, byte[] spirv) {
        return new RtShaderCode(debugName, spirv);
    }
}
