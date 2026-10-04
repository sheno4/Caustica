package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.nio.ByteOrder;
import java.util.List;

/** Scene radiance enters the original linear BT.709 compositor and returns to the host ACEScg chain. */
public final class CloudlyColorBridge implements AutoCloseable {
    private final ShaderObjectCompute shader;
    private final ResourceOwner owner;

    public CloudlyColorBridge(GpuDevice gpu, ResourceFactory resources) {
        shader = ShaderObjectCompute.load(gpu, CloudlyColorBridge.class, "/caustica/shaders/pipelines/cloudly/color_bridge.comp.spv");
        try { owner = resources.create(shader::close); }
        catch (RuntimeException | Error failure) { shader.close(); throw failure; }
    }

    /** Input and output extents agree; the output is a distinct image with the same pre-exposure scale. */
    public void record(PassFrame frame, GpuDescriptorIndex.Resource source, GpuDescriptorIndex.Resource destination,
                       int width, int height, boolean toHost, List<ResourceOwner> dependencies) {
        frame.retain(owner);
        dependencies.forEach(frame::retain);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var push = stack.malloc(16).order(ByteOrder.LITTLE_ENDIAN);
            push.putInt(source.value()).putInt(destination.value()).putInt(toHost ? 1 : 0).putInt(0).flip();
            shader.dispatch(frame.commandBuffer(), push, (width + 7) / 8, (height + 7) / 8, 1);
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).srcAccessMask(VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    @Override public void close() { owner.close(); }
}
