package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.example.showcase.gen.ShowcasePostPushData;
import dev.comfyfluffy.caustica.example.showcase.gen.ShowcaseUiPushData;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK14;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

final class ShowcasePasses {
    static final PassId BLOOM = PassId.of("caustica", "bloom");
    static final PassId POST_EFFECT = PassId.of("caustica_showcase", "colour_grade");
    static final PassId UI = PassId.of("caustica_showcase", "world_marker");
    static final PassPlacement POST_EFFECT_PLACEMENT = PassPlacement.after(BLOOM);
    static final ShaderObjectGraphics.PushIndexedResourceMapping UI_SCENE_MAPPING =
            ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 0, 0);

    private ShowcasePasses() { }

    static Pass<PassFrame> worldResource(GpuDevice gpu, Runnable publishReadyEnvironment) {
        return new Pass<>() {
            @Override
            public void record(PassFrame frame) {
                publishReadyEnvironment.run();
            }

            @Override
            public void close() { }
        };
    }

    static Pass<PostEffectFrame> postEffect(GpuDevice gpu, OptionLookup options) {
        ShaderObjectCompute shader = createComputeShader(gpu, shader("passes/post.comp.spv"));
        return new Pass<>() {
            @Override
            public void record(PostEffectFrame frame) {
                GpuImage output = frame.acquireSceneColorOutput();
                var push = new ShowcasePostPushData(
                        frame.sceneColor().descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                        frame.exposureImage().descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                        output.descriptor(GpuImageDescriptorKind.STORAGE).index().value(),
                        colourGradeStrength(options), output.width(), output.height());
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer pushBytes = stack.calloc(ShowcasePostPushData.BYTE_SIZE);
                    push.write(pushBytes);
                    shader.dispatch(frame.commandBuffer(), pushBytes,
                            divideRoundUp(output.width(), 8), divideRoundUp(output.height(), 8), 1);
                }
            }

            @Override
            public void close() {
                shader.close();
            }
        };
    }

    /** Creates a pass-owned compute shader object from tooling-produced direct SPIR-V. */
    static ShaderObjectCompute createComputeShader(GpuDevice gpu, ByteBuffer spirv) {
        return ShaderObjectCompute.create(gpu, spirv, "main");
    }

    /** Creates pass-owned graphics shader objects with a fully dynamic triangle-list draw contract. */
    static ShaderObjectGraphics createGraphicsShaders(GpuDevice gpu, ByteBuffer vertexSpirv,
                                                       ByteBuffer fragmentSpirv) {
        return ShaderObjectGraphics.create(gpu, vertexSpirv, fragmentSpirv,
                ShaderObjectGraphics.VertexFormat.NONE, VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                ShaderObjectGraphics.Blend.ALPHA, VK10.VK_SAMPLE_COUNT_1_BIT);
    }

    /** Creates the UI shaders with its static scene binding sourced from pushed descriptor index zero. */
    static ShaderObjectGraphics createUiGraphicsShaders(GpuDevice gpu, ByteBuffer vertexSpirv,
                                                         ByteBuffer fragmentSpirv) {
        return ShaderObjectGraphics.create(gpu, vertexSpirv, fragmentSpirv,
                ShaderObjectGraphics.VertexFormat.NONE, VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                ShaderObjectGraphics.Blend.ALPHA, VK10.VK_SAMPLE_COUNT_1_BIT,
                List.of(), List.of(UI_SCENE_MAPPING));
    }

    static float colourGradeStrength(OptionLookup options) {
        return options.snapshot().options(ApiShowcaseExtension.ID)
                .get(ApiShowcaseExtension.COLOUR_GRADE_STRENGTH);
    }

    static Pass<UiFrame> ui(GpuDevice gpu) {
        ShaderObjectGraphics shaders = createUiGraphicsShaders(gpu,
                shader("passes/ui_marker.vert.spv"), shader("passes/ui_marker.frag.spv"));
        return new Pass<>() {
            @Override
            public void record(UiFrame frame) {
                GpuImage layer = frame.layer();
                GpuAccelerationStructureDescriptor tlas = frame.entrySceneTlasDescriptor();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer pushBytes = stack.calloc(ShowcaseUiPushData.BYTE_SIZE);
                    new ShowcaseUiPushData(tlas.index().value()).write(pushBytes);
                    beginColorRendering(frame.commandBuffer(), stack, layer);
                    shaders.bind(frame.commandBuffer(), pushBytes, layer.width(), layer.height());
                    VK10.vkCmdDraw(frame.commandBuffer(), 3, 1, 0, 0);
                    VK14.vkCmdEndRendering(frame.commandBuffer());
                }
            }

            @Override
            public void close() {
                shaders.close();
            }
        };
    }

    /** Shape of raw descriptor writes needed by an extension-owned texture table. */
    static void writeDescriptors(GpuDescriptorWriter writer,
                                 GpuDescriptorRange<GpuDescriptorIndex.Resource> resources,
                                 GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers,
                                 VkResourceDescriptorInfoEXT resource,
                                 VkSamplerCreateInfo sampler,
                                 long accelerationStructure) {
        writer.writeResource(resources, 0, resource);
        writer.writeAccelerationStructure(resources, 1, accelerationStructure);
        writer.writeSampler(samplers, 0, sampler);
    }

    private static void beginColorRendering(org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
                                            MemoryStack stack, GpuImage layer) {
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(layer.view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        VkRect2D area = VkRect2D.calloc(stack);
        area.offset(VkOffset2D.calloc(stack).set(0, 0));
        area.extent().set(layer.width(), layer.height());
        VK14.vkCmdBeginRendering(commandBuffer, VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(area).layerCount(1).pColorAttachments(color));
    }

    private static ByteBuffer shader(String path) {
        String resource = "/api_showcase/shaders/" + path;
        try (InputStream input = ApiShowcaseExtension.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing showcase shader " + resource);
            byte[] bytes = input.readAllBytes();
            return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read showcase shader " + resource, failure);
        }
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
