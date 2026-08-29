package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.gpu.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.rt.GpuBuffer;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.gen.DebugPresentPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Presents guide-buffer diagnostics after exposure and display mapping. */
public final class RtDebugPresentPipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/debug_present/main.comp.spv";
    private final GpuContext context;
    private final ShaderObjectCompute shader;

    private RtDebugPresentPipeline(GpuContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtDebugPresentPipeline create(GpuContext context) {
        try (InputStream input = RtDebugPresentPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return new RtDebugPresentPipeline(context, ShaderObjectCompute.create(context, spirv, "main"));
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public void dispatch(VkCommandBuffer command, GpuImage output, GpuImage normal, GpuImage albedo,
                         GpuImage depth, GpuImage motion, GpuImage specAlbedo, GpuImage specMotion,
                         GpuImage scene, GpuImage exposure, GpuBuffer exposureState, int debugView,
                         float centerWeightSigma, float centerWeightFloor) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "debug present")) {
            ByteBuffer push = stack.malloc(DebugPresentPushData.BYTE_SIZE);
            new DebugPresentPushData(storage(output), storage(normal), storage(albedo), storage(depth),
                    storage(motion), storage(specAlbedo), storage(specMotion), storage(scene),
                    storage(exposure), exposureState.deviceAddress(), debugView,
                    centerWeightSigma, centerWeightFloor).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    public void destroy() {
        shader.close();
    }
}
