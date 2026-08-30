package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Descriptor-heap vertex and fragment shader objects with fully dynamic raster state. */
public final class ShaderObjectGraphics implements AutoCloseable {
    /** One dynamic vertex binding expressed directly in Vulkan terms. */
    public record VertexBinding(int binding, int stride, int inputRate, int divisor) {
        public VertexBinding {
            if (binding < 0 || stride < 0 || divisor < 0) {
                throw new IllegalArgumentException("negative vertex binding field");
            }
        }
    }

    /** One dynamic vertex attribute expressed directly in Vulkan terms. */
    public record VertexAttribute(int location, int binding, int format, int offset) {
        public VertexAttribute {
            if (location < 0 || binding < 0 || offset < 0) {
                throw new IllegalArgumentException("negative vertex attribute field");
            }
        }
    }

    /** Complete dynamic vertex input for a draw. */
    public record VertexInput(List<VertexBinding> bindings, List<VertexAttribute> attributes) {
        public static final VertexInput NONE = new VertexInput(List.of(), List.of());

        public VertexInput {
            bindings = List.copyOf(bindings);
            attributes = List.copyOf(attributes);
        }
    }

    /** Dynamic blend state for color attachment zero. */
    public record ColorBlend(boolean enabled, int srcColorFactor, int dstColorFactor, int colorOp,
                             int srcAlphaFactor, int dstAlphaFactor, int alphaOp, int writeMask) {}

    /** Vulkan-native dynamic state that is stable for this shader pair. */
    public record GraphicsState(VertexInput vertexInput, int primitiveTopology, boolean primitiveRestart,
                                int polygonMode, int cullMode, int frontFace,
                                int rasterizationSamples, int sampleMask,
                                boolean depthTest, boolean depthWrite, int depthCompareOp,
                                ColorBlend colorBlend) {
        public GraphicsState {
            Objects.requireNonNull(vertexInput, "vertexInput");
            Objects.requireNonNull(colorBlend, "colorBlend");
        }
    }

    /** Maps one statically bound SPIR-V resource to an index stored in pushed shader data. */
    public record PushIndexedResourceMapping(int descriptorSet, int binding, int resourceMask,
                                             int pushDataOffset) {
        public PushIndexedResourceMapping {
            if (descriptorSet < 0 || binding < 0) {
                throw new IllegalArgumentException("negative descriptor binding");
            }
            if (resourceMask == 0) throw new IllegalArgumentException("resource mask must not be empty");
            if (pushDataOffset < 0 || (pushDataOffset & 3) != 0) {
                throw new IllegalArgumentException("push data offset must be a non-negative multiple of four");
            }
        }

        public static PushIndexedResourceMapping accelerationStructure(int descriptorSet, int binding,
                                                                        int pushDataOffset) {
            return new PushIndexedResourceMapping(descriptorSet, binding,
                    EXTDescriptorHeap.VK_SPIRV_RESOURCE_TYPE_ACCELERATION_STRUCTURE_BIT_EXT, pushDataOffset);
        }
    }

    private final VkDevice device;
    private final long vertex;
    private final long fragment;
    private final GraphicsState state;
    private final ResourceLifetime lifetime;

    private ShaderObjectGraphics(VkDevice device, long vertex, long fragment, GraphicsState state) {
        this.device = device;
        this.vertex = vertex;
        this.fragment = fragment;
        this.state = state;
        lifetime = new ResourceLifetime(() -> EXTShaderObject.vkDestroyShaderEXT(device, fragment, null),
                () -> EXTShaderObject.vkDestroyShaderEXT(device, vertex, null));
    }

    public static ShaderObjectGraphics create(GpuDevice gpu, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv,
                                              String vertexEntryPoint, String fragmentEntryPoint,
                                              GraphicsState state) {
        return create(gpu, vertexSpirv, fragmentSpirv, vertexEntryPoint, fragmentEntryPoint, state,
                List.of(), List.of());
    }

    public static ShaderObjectGraphics create(GpuDevice gpu, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv,
                                              String vertexEntryPoint, String fragmentEntryPoint,
                                              GraphicsState state,
                                              List<PushIndexedResourceMapping> vertexMappings,
                                              List<PushIndexedResourceMapping> fragmentMappings) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(state, "state");
        long vertex = createShader(gpu, vertexSpirv, VK10.VK_SHADER_STAGE_VERTEX_BIT,
                VK10.VK_SHADER_STAGE_FRAGMENT_BIT, vertexEntryPoint, vertexMappings);
        try {
            long fragment = createShader(gpu, fragmentSpirv, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                    fragmentEntryPoint, fragmentMappings);
            return new ShaderObjectGraphics(gpu.vk(), vertex, fragment, state);
        } catch (RuntimeException | Error failure) {
            EXTShaderObject.vkDestroyShaderEXT(gpu.vk(), vertex, null);
            throw failure;
        }
    }

    private static long createShader(GpuDevice gpu, ByteBuffer spirv, int stage, int nextStage, String entryPoint,
                                     List<PushIndexedResourceMapping> mappings) {
        Objects.requireNonNull(spirv, "spirv");
        Objects.requireNonNull(entryPoint, "entryPoint");
        if (entryPoint.isBlank()) throw new IllegalArgumentException("shader entry point must not be blank");
        Objects.requireNonNull(mappings, "mappings");
        if (!spirv.isDirect()) throw new IllegalArgumentException("SPIR-V must be direct");
        if (mappings.isEmpty()) ShaderObjectCompute.validateDescriptorHeapSpirv(spirv);
        else validateMappedDescriptorHeapSpirv(spirv, mappings);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderCreateInfoEXT.Buffer info = VkShaderCreateInfoEXT.calloc(1, stack);
            info.get(0).sType$Default().flags(EXTDescriptorHeap.VK_SHADER_CREATE_DESCRIPTOR_HEAP_BIT_EXT)
                    .stage(stage).nextStage(nextStage).codeType(EXTShaderObject.VK_SHADER_CODE_TYPE_SPIRV_EXT)
                    .pCode(spirv).pName(stack.UTF8(entryPoint)).setLayoutCount(0).pushConstantRangeCount(0);
            if (!mappings.isEmpty()) {
                int stride = Math.toIntExact(gpu.descriptorHeap().properties().resourceDescriptorStrideBytes());
                info.get(0).pNext(createMappingInfo(stack, mappings, stride).address());
            }
            LongBuffer output = stack.mallocLong(1);
            VulkanChecks.check(EXTShaderObject.vkCreateShadersEXT(gpu.vk(), info, null, output),
                    "vkCreateShadersEXT");
            return output.get(0);
        }
    }

    private static VkShaderDescriptorSetAndBindingMappingInfoEXT createMappingInfo(
            MemoryStack stack, List<PushIndexedResourceMapping> mappings, int resourceDescriptorStride) {
        if (resourceDescriptorStride <= 0) {
            throw new IllegalArgumentException("resource descriptor stride must be positive");
        }
        VkDescriptorSetAndBindingMappingEXT.Buffer nativeMappings =
                VkDescriptorSetAndBindingMappingEXT.calloc(mappings.size(), stack);
        for (int index = 0; index < mappings.size(); index++) {
            PushIndexedResourceMapping mapping = mappings.get(index);
            nativeMappings.get(index).sType$Default().descriptorSet(mapping.descriptorSet())
                    .firstBinding(mapping.binding()).bindingCount(1).resourceMask(mapping.resourceMask())
                    .source(EXTDescriptorHeap.VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT)
                    .sourceData(data -> data.pushIndex(pushIndex -> pushIndex.heapOffset(0)
                            .pushOffset(mapping.pushDataOffset()).heapIndexStride(resourceDescriptorStride)
                            .heapArrayStride(resourceDescriptorStride)));
        }
        return VkShaderDescriptorSetAndBindingMappingInfoEXT.calloc(stack).sType$Default()
                .pMappings(nativeMappings);
    }

    static void validateMappedDescriptorHeapSpirv(ByteBuffer spirv,
                                                   List<PushIndexedResourceMapping> mappings) {
        Set<DescriptorBinding> covered = new HashSet<>();
        for (int index = 0; index < mappings.size(); index++) {
            PushIndexedResourceMapping mapping = Objects.requireNonNull(mappings.get(index), "mapping");
            DescriptorBinding binding = new DescriptorBinding(mapping.descriptorSet(), mapping.binding());
            for (int previous = 0; previous < index; previous++) {
                PushIndexedResourceMapping other = mappings.get(previous);
                if (other.descriptorSet() == mapping.descriptorSet() && other.binding() == mapping.binding()
                        && (other.resourceMask() & mapping.resourceMask()) != 0) {
                    throw new IllegalArgumentException("overlapping mappings for descriptor set "
                            + mapping.descriptorSet() + " binding " + mapping.binding());
                }
            }
            covered.add(binding);
        }

        ByteBuffer words = spirv.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (words.remaining() < 5 * Integer.BYTES || (words.remaining() & 3) != 0
                || words.getInt(words.position()) != 0x07230203) {
            throw new IllegalArgumentException("shader object code is not a SPIR-V module");
        }
        int base = words.position();
        int wordCount = words.remaining() / Integer.BYTES;
        Map<Integer, Integer> descriptorSets = new HashMap<>();
        Map<Integer, Integer> bindings = new HashMap<>();
        for (int word = 5; word < wordCount;) {
            int instruction = words.getInt(base + word * Integer.BYTES);
            int instructionWords = instruction >>> 16;
            if (instructionWords == 0 || word + instructionWords > wordCount) {
                throw new IllegalArgumentException("malformed SPIR-V instruction range");
            }
            if ((instruction & 0xffff) == 71 && instructionWords >= 3) {
                int target = words.getInt(base + (word + 1) * Integer.BYTES);
                int decoration = words.getInt(base + (word + 2) * Integer.BYTES);
                if (decoration == 34 || decoration == 33) {
                    if (instructionWords < 4) {
                        throw new IllegalArgumentException("malformed SPIR-V descriptor decoration");
                    }
                    int value = words.getInt(base + (word + 3) * Integer.BYTES);
                    if (decoration == 34) descriptorSets.put(target, value);
                    else bindings.put(target, value);
                }
            }
            word += instructionWords;
        }
        Set<Integer> targets = new HashSet<>(descriptorSets.keySet());
        targets.addAll(bindings.keySet());
        for (int target : targets) {
            Integer descriptorSet = descriptorSets.get(target);
            Integer binding = bindings.get(target);
            if (descriptorSet == null || binding == null
                    || !covered.contains(new DescriptorBinding(descriptorSet, binding))) {
                throw new IllegalArgumentException("SPIR-V descriptor binding is not covered by a mapping");
            }
        }
    }

    private record DescriptorBinding(int descriptorSet, int binding) {}

    public void bind(VkCommandBuffer commandBuffer, ByteBuffer pushData, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            EXTShaderObject.vkCmdBindShadersEXT(commandBuffer,
                    stack.ints(VK10.VK_SHADER_STAGE_VERTEX_BIT, VK10.VK_SHADER_STAGE_FRAGMENT_BIT),
                    stack.longs(vertex, fragment));
            VK14.vkCmdSetPrimitiveTopology(commandBuffer, state.primitiveTopology());
            VK14.vkCmdSetPrimitiveRestartEnable(commandBuffer, state.primitiveRestart());
            VK14.vkCmdSetCullMode(commandBuffer, state.cullMode());
            VK14.vkCmdSetFrontFace(commandBuffer, state.frontFace());
            VK14.vkCmdSetRasterizerDiscardEnable(commandBuffer, false);
            VK14.vkCmdSetDepthTestEnable(commandBuffer, state.depthTest());
            VK14.vkCmdSetDepthWriteEnable(commandBuffer, state.depthWrite());
            VK14.vkCmdSetDepthCompareOp(commandBuffer, state.depthCompareOp());
            VK14.vkCmdSetDepthBoundsTestEnable(commandBuffer, false);
            VK14.vkCmdSetStencilTestEnable(commandBuffer, false);
            EXTShaderObject.vkCmdSetDepthClampEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetDepthBiasEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetPolygonModeEXT(commandBuffer, state.polygonMode());
            EXTShaderObject.vkCmdSetRasterizationSamplesEXT(commandBuffer, state.rasterizationSamples());
            EXTShaderObject.vkCmdSetSampleMaskEXT(commandBuffer, state.rasterizationSamples(),
                    stack.ints(state.sampleMask()));
            EXTShaderObject.vkCmdSetAlphaToCoverageEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetAlphaToOneEnableEXT(commandBuffer, false);
            EXTShaderObject.vkCmdSetLogicOpEnableEXT(commandBuffer, false);
            ColorBlend blend = state.colorBlend();
            EXTShaderObject.vkCmdSetColorBlendEnableEXT(commandBuffer, 0,
                    stack.ints(blend.enabled() ? VK10.VK_TRUE : VK10.VK_FALSE));
            VkColorBlendEquationEXT.Buffer equation = VkColorBlendEquationEXT.calloc(1, stack)
                    .srcColorBlendFactor(blend.srcColorFactor()).dstColorBlendFactor(blend.dstColorFactor())
                    .colorBlendOp(blend.colorOp()).srcAlphaBlendFactor(blend.srcAlphaFactor())
                    .dstAlphaBlendFactor(blend.dstAlphaFactor()).alphaBlendOp(blend.alphaOp());
            EXTShaderObject.vkCmdSetColorBlendEquationEXT(commandBuffer, 0, equation);
            EXTShaderObject.vkCmdSetColorWriteMaskEXT(commandBuffer, 0, stack.ints(blend.writeMask()));
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
        VertexInput input = state.vertexInput();
        if (input.bindings().isEmpty() && input.attributes().isEmpty()) {
            EXTVertexInputDynamicState.vkCmdSetVertexInputEXT(commandBuffer, null, null);
            return;
        }
        VkVertexInputBindingDescription2EXT.Buffer bindings =
                VkVertexInputBindingDescription2EXT.calloc(input.bindings().size(), stack);
        for (int index = 0; index < input.bindings().size(); index++) {
            VertexBinding source = input.bindings().get(index);
            bindings.get(index).sType$Default().binding(source.binding()).stride(source.stride())
                    .inputRate(source.inputRate()).divisor(source.divisor());
        }
        VkVertexInputAttributeDescription2EXT.Buffer attributes =
                VkVertexInputAttributeDescription2EXT.calloc(input.attributes().size(), stack);
        for (int index = 0; index < input.attributes().size(); index++) {
            VertexAttribute source = input.attributes().get(index);
            attributes.get(index).sType$Default().location(source.location()).binding(source.binding())
                    .format(source.format()).offset(source.offset());
        }
        EXTVertexInputDynamicState.vkCmdSetVertexInputEXT(commandBuffer, bindings, attributes);
    }

    @Override public void close() { lifetime.close(); }
}
