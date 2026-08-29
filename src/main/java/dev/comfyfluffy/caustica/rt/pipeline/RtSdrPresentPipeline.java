package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.gpu.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.gen.PresentPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Converts a sampled sRGB host image to a PQ/BT.2020 storage image. */
public final class RtSdrPresentPipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/sdr_present/main.comp.spv";
    private final GpuContext context;
    private final ShaderObjectCompute shader;

    private RtSdrPresentPipeline(GpuContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtSdrPresentPipeline create(GpuContext context) {
        return new RtSdrPresentPipeline(context, load(context));
    }

    public void dispatch(VkCommandBuffer command, GpuImage output,
                         GpuDescriptorIndex.Resource source, float uiNits) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "sdr present")) {
            ByteBuffer push = stack.malloc(PresentPushData.BYTE_SIZE);
            new PresentPushData(output.descriptor(GpuImageDescriptorKind.STORAGE).index().value(),
                    source.value(), uiNits).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    public void destroy() { shader.close(); }

    private static ShaderObjectCompute load(GpuContext context) {
        try (InputStream input = RtSdrPresentPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try { spirv.put(bytes).flip(); return ShaderObjectCompute.create(context, spirv, "main"); }
            finally { MemoryUtil.memFree(spirv); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
