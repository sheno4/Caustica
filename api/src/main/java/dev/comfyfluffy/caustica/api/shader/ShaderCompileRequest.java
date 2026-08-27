package dev.comfyfluffy.caustica.api.shader;

import java.util.Objects;

/** One typed Slang entry-point compilation request. */
public record ShaderCompileRequest(
        String label, ShaderSource source, String module, String entryPoint, ShaderStage stage
) {
    public ShaderCompileRequest {
        label = Objects.requireNonNull(label, "label");
        source = Objects.requireNonNull(source, "source");
        module = SlangIdentifier.require(module, "module");
        entryPoint = SlangIdentifier.require(entryPoint, "entryPoint");
        stage = Objects.requireNonNull(stage, "stage");
    }
}
