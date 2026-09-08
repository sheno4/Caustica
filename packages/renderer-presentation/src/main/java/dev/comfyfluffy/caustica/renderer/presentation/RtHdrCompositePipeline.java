package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.presentation.gen.PresentPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/** Composites a sampled sRGB UI image over a PQ HDR image in place. */
public final class RtHdrCompositePipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/hdr_composite/main.comp.spv";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute shader;

    private RtHdrCompositePipeline(VulkanDeviceContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtHdrCompositePipeline create(VulkanDeviceContext context) {
        return new RtHdrCompositePipeline(context, PresentationShaders.load(context, SHADER));
    }

    public void dispatch(VkCommandBuffer command, GpuImage output,
                         GpuDescriptorIndex.Resource source, float uiNits) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command, "hdr ui composite")) {
            ByteBuffer push = stack.malloc(PresentPushData.BYTE_SIZE);
            new PresentPushData(output.descriptor(GpuImageDescriptorKind.STORAGE).index().value(),
                    source.value(), uiNits).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    public void destroy() { shader.close(); }

}
