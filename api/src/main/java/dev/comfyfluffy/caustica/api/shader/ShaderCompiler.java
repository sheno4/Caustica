package dev.comfyfluffy.caustica.api.shader;

import java.io.IOException;
/** Host service for compiling extension-owned shaders with the renderer's Slang toolchain. */
public interface ShaderCompiler {
    CompiledProgram compile(String label, ShaderSource source, String module, String entryPoint)
            throws IOException;

    record CompiledProgram(byte[] spirv, String reflectionJson) {
    }
}
