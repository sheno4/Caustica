package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.EXTShaderObject;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkHostAddressRangeConstEXT;
import org.lwjgl.vulkan.VkPushDataInfoEXT;
import org.lwjgl.vulkan.VkShaderCreateInfoEXT;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/**
 * A compute {@code VkShaderEXT} compiled for descriptor heaps. It has no descriptor-set layouts or
 * pipeline layout; dispatch push data and heap indices are the complete invocation ABI.
 */
public final class ShaderObjectCompute implements AutoCloseable {
    private final long shader;
    private final ResourceLifetime lifetime;

    private ShaderObjectCompute(VkDevice device, long shader) {
        this.shader = shader;
        this.lifetime = new ResourceLifetime(() -> EXTShaderObject.vkDestroyShaderEXT(device, shader, null));
    }

    /** Create a compute shader object from a direct SPIR-V buffer. */
    public static ShaderObjectCompute create(GpuDevice gpu, ByteBuffer spirv, String entryPoint) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(spirv, "spirv");
        Objects.requireNonNull(entryPoint, "entryPoint");
        if (!spirv.isDirect()) throw new IllegalArgumentException("SPIR-V must be a direct buffer");
        validateDescriptorHeapSpirv(spirv);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderCreateInfoEXT.Buffer info = VkShaderCreateInfoEXT.calloc(1, stack);
            info.get(0).sType$Default()
                    .flags(EXTDescriptorHeap.VK_SHADER_CREATE_DESCRIPTOR_HEAP_BIT_EXT)
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .nextStage(0)
                    .codeType(EXTShaderObject.VK_SHADER_CODE_TYPE_SPIRV_EXT)
                    .pCode(spirv)
                    .pName(stack.UTF8(entryPoint))
                    .setLayoutCount(0)
                    .pushConstantRangeCount(0);
            LongBuffer output = stack.mallocLong(1);
            VulkanChecks.check(EXTShaderObject.vkCreateShadersEXT(gpu.vk(), info, null, output),
                    "vkCreateShadersEXT");
            return new ShaderObjectCompute(gpu.vk(), output.get(0));
        }
    }

    /**
     * Bind this compute shader, publish descriptor indices and parameters through push data, and dispatch.
     * The pass host must already have bound its resource and sampler heaps.
     */
    public void dispatch(VkCommandBuffer commandBuffer, ByteBuffer pushData,
                         int groupCountX, int groupCountY, int groupCountZ) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        validatePushData(pushData);
        validateGroupCounts(groupCountX, groupCountY, groupCountZ);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer stages = stack.ints(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer shaders = stack.longs(shader);
            EXTShaderObject.vkCmdBindShadersEXT(commandBuffer, stages, shaders);
            VkHostAddressRangeConstEXT range = VkHostAddressRangeConstEXT.calloc(stack).address$(pushData);
            VkPushDataInfoEXT push = VkPushDataInfoEXT.calloc(stack).sType$Default()
                    .offset(0).data(range);
            EXTDescriptorHeap.vkCmdPushDataEXT(commandBuffer, push);
            VK10.vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ);
        }
    }

    static void validatePushData(ByteBuffer pushData) {
        Objects.requireNonNull(pushData, "pushData");
        if (!pushData.isDirect()) throw new IllegalArgumentException("push data must be a direct buffer");
        if (pushData.remaining() == 0 || (pushData.remaining() & 3) != 0) {
            throw new IllegalArgumentException("push-data size must be a positive multiple of four");
        }
    }

    static void validateDescriptorHeapSpirv(ByteBuffer spirv) {
        Objects.requireNonNull(spirv, "spirv");
        if (spirv.remaining() < 5 * Integer.BYTES || (spirv.remaining() & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V must contain a complete module");
        }
        ByteBuffer words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = words.position();
        int wordCount = words.remaining() / Integer.BYTES;
        if (words.getInt(base) != 0x07230203) {
            throw new IllegalArgumentException("SPIR-V has an invalid magic number");
        }
        int word = 5;
        while (word < wordCount) {
            int instruction = words.getInt(base + word * Integer.BYTES);
            int instructionWords = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (instructionWords == 0 || instructionWords > wordCount - word) {
                throw new IllegalArgumentException("SPIR-V contains a malformed instruction");
            }
            if (opcode == 71 && instructionWords >= 3) { // OpDecorate
                int decoration = words.getInt(base + (word + 2) * Integer.BYTES);
                if (decoration == 33 || decoration == 34) { // Binding, DescriptorSet
                    throw new IllegalArgumentException(
                            "descriptor-heap shader objects require direct heap access, not set/binding decorations");
                }
            }
            word += instructionWords;
        }
    }

    static void validateGroupCounts(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) {
            throw new IllegalArgumentException("dispatch group counts must be positive");
        }
    }

    @Override
    public void close() {
        lifetime.close();
    }
}
