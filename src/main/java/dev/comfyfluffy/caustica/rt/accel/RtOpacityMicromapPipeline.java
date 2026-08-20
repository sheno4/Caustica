package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.GpuContext.check;

/** Resource-epoch compute pipeline that classifies packed CUTOUT triangles into 4-state OMM data. */
public final class RtOpacityMicromapPipeline {
    private static final int PUSH_BYTES = 48;
    private static final String SHADER = "/caustica/shaders/pipelines/opacity_micromap/main.comp.spv";

    private final GpuContext ctx;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private RtOpacityMicromapPipeline(GpuContext ctx, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RtOpacityMicromapPipeline create(GpuContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(push);
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, out),
                    "vkCreatePipelineLayout(opacity micromap)");
            long pipelineLayout = out.get(0);
            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null, out),
                    "vkCreateComputePipelines(opacity micromap)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "opacity micromap classifier");
            return new RtOpacityMicromapPipeline(ctx, pipelineLayout, pipeline);
        }
    }

    public void record(VkCommandBuffer command, long primitiveAddress, long materialTableAddress,
                       long dataAddress, long triangleAddress, int maskedTriangleBase,
                       int triangleCount, int dataStride) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, command, "opacity micromap classify")) {
            VK10.vkCmdBindPipeline(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            push.putLong(0, primitiveAddress).putLong(8, materialTableAddress)
                    .putLong(16, dataAddress).putLong(24, triangleAddress)
                    .putInt(32, maskedTriangleBase).putInt(36, triangleCount)
                    .putInt(40, dataStride).putInt(44, 0);
            push.position(0).limit(PUSH_BYTES);
            VK10.vkCmdPushConstants(command, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(command, (triangleCount + 63) / 64, 1, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RtOpacityMicromapPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer module = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, module), "vkCreateShaderModule(opacity micromap)");
            return module.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
