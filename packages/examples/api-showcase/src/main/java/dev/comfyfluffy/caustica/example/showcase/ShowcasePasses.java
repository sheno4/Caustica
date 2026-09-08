package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
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
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.example.showcase.gen.ShowcasePostPushData;
import dev.comfyfluffy.caustica.example.showcase.gen.ShowcaseUiPushData;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK14;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;

final class ShowcasePasses {
    private static final int POSITION_BYTES = 4 * 3 * Float.BYTES;
    private static final int INDEX_BYTES = 12 * Integer.BYTES;
    private static final int INDEX_OFFSET = POSITION_BYTES;
    private static final int TOTAL_BYTES = INDEX_OFFSET + INDEX_BYTES;
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

    static Pass<PassFrame> worldResource(GpuDevice gpu, ResourceFactory resources, GpuComputeQueue compute,
                                         BooleanSupplier programReady, ShowcaseScene scene) {
        return worldResource(programReady, () -> uploadMesh(gpu, resources, compute, scene));
    }

    static Pass<PassFrame> worldResource(BooleanSupplier programReady,
                                       Supplier<CompletableFuture<Void>> publish) {
        return new Pass<>() {
            private CompletableFuture<Void> publication;

            @Override
            public void record(PassFrame frame) {
                if (publication == null && programReady.getAsBoolean()) publication = publish.get();
                // Observe upload/publication failures without waiting on the render thread.
                if (publication != null && publication.isDone()) publication.join();
            }

            @Override
            public void close() {
                if (publication != null) publication.cancel(false);
            }
        };
    }

    private static CompletableFuture<Void> uploadMesh(GpuDevice gpu, ResourceFactory resources,
                                                      GpuComputeQueue compute, ShowcaseScene scene) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        VmaMappedBuffer upload = VmaMappedBuffer.createAsync(gpu, TOTAL_BYTES,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                "API showcase retained mesh");
        ResourceOwner pending;
        try {
            pending = resources.create(upload::close);
        } catch (Throwable failure) {
            upload.close();
            result.completeExceptionally(failure);
            return result;
        }
        try {
            compute.submit(commandBuffer -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = stack.calloc(TOTAL_BYTES);
                    data.asFloatBuffer().put(new float[] {
                            -0.75f, 0.0f, 0.0f,
                            0.75f, 0.0f, 0.0f,
                            0.0f, 1.25f, 0.0f,
                            0.0f, 0.0f, 0.0f });
                    // Each geometry slot exercises a distinct surface/volume combination.
                    for (int triangle = 0; triangle < 4; triangle++) {
                        int base = INDEX_OFFSET + triangle * 3 * Integer.BYTES;
                        data.putInt(base, 0).putInt(base + Integer.BYTES, 1)
                                .putInt(base + Integer.BYTES * 2, 2);
                    }
                    VK10.vkCmdUpdateBuffer(commandBuffer, upload.buffer(), 0L, data);
                }
            }, List.of(pending), completion -> {
                if (result.isCancelled()) return;
                if (completion instanceof GpuComputeCompletion.Failed failed) {
                    result.completeExceptionally(failed.failure());
                } else if (completion instanceof GpuComputeCompletion.Cancelled) {
                    result.completeExceptionally(new CancellationException("Mesh upload cancelled"));
                } else {
                    try {
                        // The mesh build retains its input before the upload job releases its claim.
                        scene.publishMesh(upload.deviceRange().slice(0, POSITION_BYTES),
                                upload.deviceRange().slice(INDEX_OFFSET, INDEX_BYTES), pending.retain())
                                .whenComplete((ignored, failure) -> {
                                    if (failure == null) result.complete(null);
                                    else result.completeExceptionally(failure);
                                });
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                }
            });
        } catch (Throwable failure) {
            pending.close();
            result.completeExceptionally(failure);
        }
        return result;
    }

    static Pass<PostEffectFrame> postEffect(GpuDevice gpu, OptionLookup options) {
        ShaderObjectCompute shader = ShaderObjectCompute.create(gpu, shader("passes/post.comp.spv"), "main");
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
                            Math.ceilDiv(output.width(), 8), Math.ceilDiv(output.height(), 8), 1);
                }
            }

            @Override
            public void close() {
                shader.close();
            }
        };
    }

    static float colourGradeStrength(OptionLookup options) {
        return options.snapshot().options(ApiShowcaseExtension.ID)
                .get(ApiShowcaseExtension.COLOUR_GRADE_STRENGTH);
    }

    static Pass<UiFrame> ui(GpuDevice gpu) {
        ShaderObjectGraphics shaders = ShaderObjectGraphics.create(gpu,
                shader("passes/ui_marker.vert.spv"), shader("passes/ui_marker.frag.spv"),
                "main", "main", FULLSCREEN_ALPHA, List.of(), List.of(UI_SCENE_MAPPING));
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

    private static void beginColorRendering(VkCommandBuffer commandBuffer,
                                            MemoryStack stack, GpuImage layer) {
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(layer.view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        VkRect2D area = VkRect2D.calloc(stack);
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

}
