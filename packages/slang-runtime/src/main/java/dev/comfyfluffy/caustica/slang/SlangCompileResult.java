package dev.comfyfluffy.caustica.slang;

import java.util.Arrays;
import java.util.Objects;

public record SlangCompileResult(byte[] spirv, String reflectionJson, String diagnostics) {
    public SlangCompileResult {
        spirv = Arrays.copyOf(Objects.requireNonNull(spirv, "spirv"), spirv.length);
        reflectionJson = reflectionJson == null ? "" : reflectionJson;
        diagnostics = diagnostics == null ? "" : diagnostics;
    }

    @Override
    public byte[] spirv() {
        return Arrays.copyOf(spirv, spirv.length);
    }
}
