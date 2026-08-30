package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.presentation.gen.NrdComposePushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Reconstructs pre-exposed scene radiance from NRD's demodulated diffuse and specular outputs. */
public final class RtNrdComposePipeline {
    public static final int SIGNAL_LINEAR_RGB = 0;
    public static final int SIGNAL_YCOCG = 1;
    private static final String SHADER = "/caustica/shaders/pipelines/nrd_compose/main.comp.spv";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute shader;

    private RtNrdComposePipeline(VulkanDeviceContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtNrdComposePipeline create(VulkanDeviceContext context) {
        return new RtNrdComposePipeline(context, load(context));
    }

    public void dispatch(VkCommandBuffer command, GpuImage output,
                         GpuImage denoisedDiffuse, GpuImage denoisedSpecular,
                         GpuImage diffuseAlbedo, GpuImage specularAlbedo,
                         GpuImage stableRadiance,
                         float preExposure, int signalEncoding) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "NRD compose")) {
            ByteBuffer push = stack.malloc(NrdComposePushData.BYTE_SIZE);
            new NrdComposePushData(storage(output), storage(denoisedDiffuse),
                    storage(denoisedSpecular), storage(stableRadiance), storage(diffuseAlbedo),
                    storage(specularAlbedo), preExposure, signalEncoding).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    public void destroy() {
        shader.close();
    }

    private static ShaderObjectCompute load(VulkanDeviceContext context) {
        try (InputStream input = RtNrdComposePipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return ShaderObjectCompute.create(context, spirv, "main");
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
