package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.OpacityMicromapPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Resource-epoch shader-object compute classifier for packed CUTOUT triangle micromap data. */
final class RtOpacityMicromapPipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/opacity_micromap/main.comp.spv";

    private final VulkanDeviceContext context;
    private final ShaderObjectCompute shader;

    private RtOpacityMicromapPipeline(VulkanDeviceContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtOpacityMicromapPipeline create(VulkanDeviceContext context) {
        try (InputStream input = RtOpacityMicromapPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return new RtOpacityMicromapPipeline(context,
                        ShaderObjectCompute.create(context, spirv, "main"));
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public void record(VkCommandBuffer command, long primitiveAddress, long materialTableAddress,
                       long dataAddress, long triangleAddress, int maskedTriangleBase,
                       int triangleCount, int dataStride) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "opacity micromap classify")) {
            ByteBuffer push = stack.malloc(OpacityMicromapPushData.BYTE_SIZE);
            new OpacityMicromapPushData(primitiveAddress, materialTableAddress,
                    dataAddress, triangleAddress, maskedTriangleBase, triangleCount, dataStride).write(push);
            shader.dispatch(command, push, (triangleCount + 63) / 64, 1, 1);
        }
    }

    public void destroy() {
        shader.close();
    }
}
