package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.retained.RetainedPublication;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.example.showcase.gen.ShowcasePostPushData;
import dev.comfyfluffy.caustica.example.showcase.gen.ShowcaseUiPushData;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;

final class ShowcasePasses {
    private static final int COLOR_WRITE_RGBA = VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT;
    private static final ShaderObjectGraphics.GraphicsState FULLSCREEN_ALPHA =
            new ShaderObjectGraphics.GraphicsState(ShaderObjectGraphics.VertexInput.NONE,
                    VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false, VK10.VK_POLYGON_MODE_FILL,
                    VK10.VK_CULL_MODE_NONE, VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE,
                    VK10.VK_SAMPLE_COUNT_1_BIT, ~0, false, false, VK10.VK_COMPARE_OP_ALWAYS,
                    new ShaderObjectGraphics.ColorBlend(true, VK10.VK_BLEND_FACTOR_SRC_ALPHA,
                            VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_OP_ADD,
                            VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                            VK10.VK_BLEND_OP_ADD, COLOR_WRITE_RGBA));
    static final PassId BLOOM = PassId.of("caustica", "bloom");
    static final PassId POST_EFFECT = PassId.of("caustica_showcase", "colour_grade");
    static final PassId UI = PassId.of("caustica_showcase", "world_marker");
    static final PassPlacement POST_EFFECT_PLACEMENT = PassPlacement.after(BLOOM);
    static final ShaderObjectGraphics.PushIndexedResourceMapping UI_SCENE_MAPPING =
            ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 0, 0);

    private ShowcasePasses() { }

    static Pass<PassFrame> worldResource(GpuDevice gpu, ResourceFactory resources,
                                         BooleanSupplier programReady, ShowcaseScene scene) {
        return worldResource(programReady, new VulkanWorldMeshHandoff(gpu, resources, scene));
    }

    static Pass<PassFrame> worldResource(BooleanSupplier programReady, WorldMeshHandoff handoff) {
        return new Pass<>() {
            @Override
            public void record(PassFrame frame) {
                if (programReady.getAsBoolean() && !handoff.published()) handoff.recordAndPublish(frame);
            }

            @Override
            public void close() {
                handoff.close();
            }
        };
    }

    interface WorldMeshHandoff extends AutoCloseable {
        /** True only after the accepted geometry publication is visible in the native scene. */
        boolean published();
        /** Advances upload, submission, or receipt polling without submitting the retained mesh twice. */
        void recordAndPublish(PassFrame frame);
        @Override void close();
    }

    private static final class VulkanWorldMeshHandoff implements WorldMeshHandoff {
        private static final int POSITION_BYTES = 4 * 3 * Float.BYTES;
        private static final int INDEX_BYTES = 12 * Integer.BYTES;
        private static final int INDEX_OFFSET = POSITION_BYTES;
        private static final int TOTAL_BYTES = INDEX_OFFSET + INDEX_BYTES;

        private final GpuDevice gpu;
        private final ResourceFactory resources;
        private final ShowcaseScene scene;
        private VmaMappedBuffer upload;
        private boolean uploadRecorded;
        private boolean uploadSubmitted;
        private boolean uploadComplete;
        private boolean closed;
        private RetainedPublication publication;

        private VulkanWorldMeshHandoff(GpuDevice gpu, ResourceFactory resources, ShowcaseScene scene) {
            this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
            this.resources = java.util.Objects.requireNonNull(resources, "resources");
            this.scene = java.util.Objects.requireNonNull(scene, "scene");
        }

        @Override public boolean published() {
            return publication != null && publication.isVisible();
        }

        @Override
        public void recordAndPublish(PassFrame frame) {
            if (publication != null) return;
            if (uploadRecorded) {
                if (!uploadComplete) return;
                VmaMappedBuffer accepted = upload;
                ResourceGeneration generation = resources.create(accepted::close);
                upload = null;
                publication = scene.publishMesh(accepted.deviceRange().slice(0, POSITION_BYTES),
                        accepted.deviceRange().slice(INDEX_OFFSET, INDEX_BYTES), generation);
                return;
            }
            if (upload == null) {
                upload = VmaMappedBuffer.create(gpu, TOTAL_BYTES,
                        VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        "API showcase retained mesh");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.calloc(TOTAL_BYTES);
                putTrianglePositions(data, 0);
                for (int triangle = 0; triangle < 4; triangle++) {
                    int base = INDEX_OFFSET + triangle * 3 * Integer.BYTES;
                    data.putInt(base, 0).putInt(base + Integer.BYTES, 1)
                            .putInt(base + Integer.BYTES * 2, 2);
                }
                VK10.vkCmdUpdateBuffer(frame.commandBuffer(), upload.buffer(), 0L, data);
                VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                VK14.vkCmdPipelineBarrier2(frame.commandBuffer(),
                        VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
            }
            uploadRecorded = true;
            uploadSubmitted = false;
            frame.gpuUse().whenSubmitted(() -> uploadSubmitted = true);
            frame.gpuUse().whenComplete(() -> {
                if (uploadSubmitted) uploadComplete = true;
                else uploadRecorded = false;
                if (closed && upload != null) {
                    upload.close();
                    upload = null;
                }
            });
        }

        private static void putTrianglePositions(ByteBuffer target, int offset) {
            float[] positions = { -0.75f, 0.0f, 0.0f, 0.75f, 0.0f, 0.0f,
                    0.0f, 1.25f, 0.0f, 0.0f, 0.0f, 0.0f };
            for (int index = 0; index < positions.length; index++) {
                target.putFloat(offset + index * Float.BYTES, positions[index]);
            }
        }

        @Override
        public void close() {
            closed = true;
            if ((!uploadRecorded || uploadComplete) && upload != null) {
                upload.close();
                upload = null;
            }
        }
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
                "main", "main", FULLSCREEN_ALPHA);
    }

    /** Creates the UI shaders with its static scene binding sourced from pushed descriptor index zero. */
    static ShaderObjectGraphics createUiGraphicsShaders(GpuDevice gpu, ByteBuffer vertexSpirv,
                                                         ByteBuffer fragmentSpirv) {
        return ShaderObjectGraphics.create(gpu, vertexSpirv, fragmentSpirv,
                "main", "main", FULLSCREEN_ALPHA,
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
