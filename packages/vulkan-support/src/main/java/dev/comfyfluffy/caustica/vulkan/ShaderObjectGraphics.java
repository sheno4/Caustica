package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/** Descriptor-heap vertex and fragment shader objects with fully dynamic raster state. */
public final class ShaderObjectGraphics implements AutoCloseable {
    public enum VertexFormat { NONE, POSITION, POSITION_TEX_COLOR }
    public enum Blend { NONE, ALPHA }

    private final VkDevice device;
    private final long vertex;
    private final long fragment;
    private final VertexFormat format;
    private final int topology;
    private final Blend blend;
    private final int samples;
    private final ResourceLifetime lifetime;

    private ShaderObjectGraphics(VkDevice device, long vertex, long fragment, VertexFormat format,
                                 int topology, Blend blend, int samples) {
        this.device = device;
        this.vertex = vertex;
        this.fragment = fragment;
        this.format = format;
        this.topology = topology;
        this.blend = blend;
        this.samples = samples;
        lifetime = new ResourceLifetime(() -> EXTShaderObject.vkDestroyShaderEXT(device, fragment, null),
                () -> EXTShaderObject.vkDestroyShaderEXT(device, vertex, null));
    }

    public static ShaderObjectGraphics create(GpuDevice gpu, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv,
                                              VertexFormat format, int topology, Blend blend, int samples) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(blend, "blend");
        long vertex = createShader(gpu, vertexSpirv, VK10.VK_SHADER_STAGE_VERTEX_BIT,
                VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        try {
            long fragment = createShader(gpu, fragmentSpirv, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0);
            return new ShaderObjectGraphics(gpu.vk(), vertex, fragment, format, topology, blend, samples);
        } catch (RuntimeException | Error failure) {
            EXTShaderObject.vkDestroyShaderEXT(gpu.vk(), vertex, null);
            throw failure;
        }
    }

    private static long createShader(GpuDevice gpu, ByteBuffer spirv, int stage, int nextStage) {
        Objects.requireNonNull(spirv, "spirv");
        if (!spirv.isDirect()) throw new IllegalArgumentException("SPIR-V must be direct");
        ShaderObjectCompute.validateDescriptorHeapSpirv(spirv);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderCreateInfoEXT.Buffer info = VkShaderCreateInfoEXT.calloc(1, stack);
            info.get(0).sType$Default().flags(EXTDescriptorHeap.VK_SHADER_CREATE_DESCRIPTOR_HEAP_BIT_EXT)
                    .stage(stage).nextStage(nextStage).codeType(EXTShaderObject.VK_SHADER_CODE_TYPE_SPIRV_EXT)
                    .pCode(spirv).pName(stack.UTF8("main")).setLayoutCount(0).pushConstantRangeCount(0);
            LongBuffer output = stack.mallocLong(1);
            VulkanChecks.check(EXTShaderObject.vkCreateShadersEXT(gpu.vk(), info, null, output),
                    "vkCreateShadersEXT");
            return output.get(0);
        }
    }

    public void bind(VkCommandBuffer commandBuffer, ByteBuffer pushData, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            EXTShaderObject.vkCmdBindShadersEXT(commandBuffer,
                    stack.ints(VK10.VK_SHADER_STAGE_VERTEX_BIT, VK10.VK_SHADER_STAGE_FRAGMENT_BIT),
                    stack.longs(vertex, fragment));
            VK14.vkCmdSetPrimitiveTopology(commandBuffer, topology);
            VK14.vkCmdSetPrimitiveRestartEnable(commandBuffer, false);
            VK14.vkCmdSetCullMode(commandBuffer, VK10.VK_CULL_MODE_NONE);
            VK14.vkCmdSetFrontFace(commandBuffer, VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VK14.vkCmdSetRasterizerDiscardEnable(commandBuffer, false);
            VK14.vkCmdSetDepthTestEnable(commandBuffer, false);
            VK14.vkCmdSetDepthWriteEnable(commandBuffer, false);
            VK14.vkCmdSetDepthBoundsTestEnable(commandBuffer, false);
            VK14.vkCmdSetStencilTestEnable(commandBuffer, false);
            EXTShaderObject.vkCmdSetDepthClampEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetDepthBiasEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetPolygonModeEXT(commandBuffer, VK10.VK_POLYGON_MODE_FILL);
            EXTShaderObject.vkCmdSetRasterizationSamplesEXT(commandBuffer, samples);
            EXTShaderObject.vkCmdSetSampleMaskEXT(commandBuffer, samples, stack.ints(~0));
            EXTShaderObject.vkCmdSetAlphaToCoverageEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetAlphaToOneEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetLogicOpEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetColorBlendEnableEXT(commandBuffer, 0,
                    stack.ints(blend == Blend.ALPHA ? VK10.VK_TRUE : VK10.VK_FALSE));
            VkColorBlendEquationEXT.Buffer equation = VkColorBlendEquationEXT.calloc(1, stack)
                    .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK10.VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
            EXTShaderObject.vkCmdSetColorBlendEquationEXT(commandBuffer, 0, equation);
            EXTShaderObject.vkCmdSetColorWriteMaskEXT(commandBuffer, 0, stack.ints(
                    VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT));
            setVertexInput(commandBuffer, stack);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).set(0, 0, width, height, 0, 1);
            VK14.vkCmdSetViewportWithCount(commandBuffer, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(width, height);
            VK14.vkCmdSetScissorWithCount(commandBuffer, scissor);
            if (pushData != null) {
                VkHostAddressRangeConstEXT range = VkHostAddressRangeConstEXT.calloc(stack).address$(pushData);
                EXTDescriptorHeap.vkCmdPushDataEXT(commandBuffer,
                        VkPushDataInfoEXT.calloc(stack).sType$Default().offset(0).data(range));
            }
        }
    }

    private void setVertexInput(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (format == VertexFormat.NONE) {
            EXTVertexInputDynamicState.vkCmdSetVertexInputEXT(commandBuffer, null, null);
            return;
        }
        int stride = format == VertexFormat.POSITION ? 12 : 24;
        VkVertexInputBindingDescription2EXT.Buffer binding = VkVertexInputBindingDescription2EXT.calloc(1, stack);
        binding.get(0).sType$Default().binding(0).stride(stride).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX)
                .divisor(1);
        int count = format == VertexFormat.POSITION ? 1 : 3;
        VkVertexInputAttributeDescription2EXT.Buffer attributes =
                VkVertexInputAttributeDescription2EXT.calloc(count, stack);
        attributes.get(0).sType$Default().location(0).binding(0)
                .format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        if (count == 3) {
            attributes.get(1).sType$Default().location(1).binding(0)
                    .format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(12);
            attributes.get(2).sType$Default().location(2).binding(0)
                    .format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
        }
        EXTVertexInputDynamicState.vkCmdSetVertexInputEXT(commandBuffer, binding, attributes);
    }

    @Override public void close() { lifetime.close(); }
}
