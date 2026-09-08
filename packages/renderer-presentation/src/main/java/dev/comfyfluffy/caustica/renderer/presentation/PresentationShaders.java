package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Loads packaged compute shaders and releases their temporary native SPIR-V storage. */
final class PresentationShaders {
    private PresentationShaders() { }

    static ShaderObjectCompute load(GpuDevice device, String resource) {
        try (var input = PresentationShaders.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + resource);
            byte[] bytes = input.readAllBytes();
            var spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return ShaderObjectCompute.create(device, spirv, "main");
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
