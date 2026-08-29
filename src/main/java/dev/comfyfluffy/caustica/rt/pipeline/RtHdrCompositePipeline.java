package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
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

/** Composites a sampled sRGB UI image over a PQ HDR image in place. */
public final class RtHdrCompositePipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/hdr_composite/main.comp.spv";
    private final GpuContext context;
    private final ShaderObjectCompute shader;

    private RtHdrCompositePipeline(GpuContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtHdrCompositePipeline create(GpuContext context) {
        return new RtHdrCompositePipeline(context, load(context));
    }

    public void dispatch(VkCommandBuffer command, GpuImage output,
                         GpuDescriptorIndex.Resource source, float uiNits) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "hdr ui composite")) {
            ByteBuffer push = stack.malloc(PresentPushData.BYTE_SIZE);
            new PresentPushData(output.descriptor(GpuImageDescriptorKind.STORAGE).index().value(),
                    source.value(), uiNits).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    public void destroy() { shader.close(); }

    private static ShaderObjectCompute load(GpuContext context) {
        try (InputStream input = RtHdrCompositePipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try { spirv.put(bytes).flip(); return ShaderObjectCompute.create(context, spirv, "main"); }
            finally { MemoryUtil.memFree(spirv); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
