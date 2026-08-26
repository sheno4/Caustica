package dev.comfyfluffy.caustica.api.shader;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.io.IOException;
import java.util.List;

/** Host service for compiling and validating extension-owned compute shaders. */
public interface ShaderCompiler {
    CompiledProgram compile(ResourceId id, ShaderSource source, String module, String entryPoint)
            throws IOException;

    /**
     * Verifies position-based set-zero bindings, push-constant size, compute entry point, and local size
     * against the shader reflection produced by {@link #compile}.
     */
    void validateBindings(ResourceId id, String reflectionJson, List<ShaderBinding> expected,
                          int pushConstantBytes, String entryPoint,
                          int localSizeX, int localSizeY, int localSizeZ) throws IOException;

    record CompiledProgram(byte[] spirv, String reflectionJson) {
    }
}
