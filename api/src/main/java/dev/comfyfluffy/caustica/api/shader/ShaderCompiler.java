package dev.comfyfluffy.caustica.api.shader;

import java.io.IOException;

/**
 * Host service for compiling extension-owned shaders with the render session's Slang toolchain.
 *
 * <p>The session compiler targets SPIR-V for Vulkan 1.4, enables Slang's {@code spvDescriptorHeapEXT}
 * capability, and uses the session's unified resource-heap stride. Consequently public
 * {@code ResourceDescriptorHeap[index]} and {@code SamplerDescriptorHeap[index]} expressions consume the
 * descriptor indices returned by the Java heap API directly. A successful result has already passed
 * SPIR-V validation for the session target. Extension resources use those direct heaps and push data;
 * descriptor-set/binding declarations are not part of the public pipeline ABI.
 */
public interface ShaderCompiler {
    ShaderCompileResult compile(ShaderCompileRequest request) throws IOException;
}
