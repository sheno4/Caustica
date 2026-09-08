package dev.comfyfluffy.caustica.minecraft.client.overlay;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTextureLifetime;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Shader-object creation and immutable descriptor entries shared by Minecraft overlay features. */
final class OverlayPipelines {
    private static final int COLOR_WRITE_RGBA = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    static final ShaderObjectGraphics.VertexInput POSITION = vertexInput(VK_VERTEX_INPUT_RATE_VERTEX, 12,
            new ShaderObjectGraphics.VertexAttribute(0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0));
    static final ShaderObjectGraphics.VertexInput POSITION_TEX_COLOR = vertexInput(VK_VERTEX_INPUT_RATE_VERTEX, 24,
            new ShaderObjectGraphics.VertexAttribute(0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0),
            new ShaderObjectGraphics.VertexAttribute(1, 0, VK_FORMAT_R32G32_SFLOAT, 12),
            new ShaderObjectGraphics.VertexAttribute(2, 0, VK_FORMAT_R8G8B8A8_UNORM, 20));
    static final ShaderObjectGraphics.VertexInput EDGE_POSITION_PAIR = vertexInput(VK_VERTEX_INPUT_RATE_INSTANCE, 24,
            new ShaderObjectGraphics.VertexAttribute(0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0),
            new ShaderObjectGraphics.VertexAttribute(1, 0, VK_FORMAT_R32G32B32_SFLOAT, 12));
    static final ShaderObjectGraphics.ColorBlend ALPHA_BLEND = new ShaderObjectGraphics.ColorBlend(true,
            VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD, COLOR_WRITE_RGBA);
    private static final ShaderObjectGraphics.ColorBlend OPAQUE = new ShaderObjectGraphics.ColorBlend(false,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, COLOR_WRITE_RGBA);

    static final class Spec {
        private final String vertex, fragment;
        private ShaderObjectGraphics.VertexInput vertexInput = ShaderObjectGraphics.VertexInput.NONE;
        private ShaderObjectGraphics.ColorBlend blend = OPAQUE;
        private List<ShaderObjectGraphics.PushIndexedResourceMapping> fragmentMappings = List.of();
        Spec(String vertex, String fragment) { this.vertex = vertex; this.fragment = fragment; }
        Spec vertex(ShaderObjectGraphics.VertexInput value) { vertexInput = value; return this; }
        Spec blend(ShaderObjectGraphics.ColorBlend value) { blend = value; return this; }
        Spec fragmentAccelerationStructure(int descriptorSet, int binding, int pushDataOffset) {
            fragmentMappings = List.of(ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(
                    descriptorSet, binding, pushDataOffset));
            return this;
        }
        ShaderObjectGraphics build(GpuDevice gpu) {
            ByteBuffer vertexCode = load(vertex);
            try {
                ByteBuffer fragmentCode = load(fragment);
                try {
                    return ShaderObjectGraphics.create(gpu, vertexCode, fragmentCode,
                            "main", "main", graphicsState(), List.of(), fragmentMappings);
                } finally {
                    MemoryUtil.memFree(fragmentCode);
                }
            } finally {
                MemoryUtil.memFree(vertexCode);
            }
        }

        private ShaderObjectGraphics.GraphicsState graphicsState() {
            return new ShaderObjectGraphics.GraphicsState(vertexInput, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                    false, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE,
                    VK_SAMPLE_COUNT_1_BIT, ~0,
                    false, false, VK_COMPARE_OP_ALWAYS, blend);
        }
    }

    private static ShaderObjectGraphics.VertexInput vertexInput(int inputRate, int stride,
                                                                 ShaderObjectGraphics.VertexAttribute... attributes) {
        return new ShaderObjectGraphics.VertexInput(
                List.of(new ShaderObjectGraphics.VertexBinding(0, stride, inputRate, 1)), List.of(attributes));
    }

    /** Immutable font-atlas descriptor that pins Minecraft's host image until retirement. */
    static final class FontImage implements AutoCloseable {
        private final VulkanGpuTexture texture;
        private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor;
        private boolean closed;
        private FontImage(VulkanGpuTexture texture,
                          GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor) {
            this.texture = texture; this.descriptor = descriptor;
        }
        static FontImage create(GpuDevice gpu, VulkanGpuTextureView view) {
            VulkanGpuTexture texture = view.texture();
            if (texture.getFormat() != GpuFormat.RGBA8_UNORM) {
                throw new IllegalArgumentException("font atlas must be RGBA8_UNORM");
            }
            MinecraftTextureLifetime.retain(texture);
            GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                descriptor = gpu.descriptorHeap().allocateResources(1);
                VkImageViewCreateInfo imageView = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(texture.vkImage()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
                imageView.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(view.baseMipLevel()).levelCount(view.mipLevels())
                        .baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(imageView).layout(VK_IMAGE_LAYOUT_GENERAL);
                gpu.descriptorHeap().writer().writeResource(descriptor, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(image)));
                return new FontImage(texture, descriptor);
            } catch (RuntimeException | Error failure) {
                try {
                    if (descriptor != null) descriptor.destroy();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
                }
                try {
                    MinecraftTextureLifetime.release(texture);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
        int index() { return descriptor.firstIndex().value(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { descriptor.destroy(); } finally { MinecraftTextureLifetime.release(texture); }
        }
    }

    private static ByteBuffer load(String name) {
        String path = "/caustica/shaders/pipelines/" + name;
        try (InputStream input = OverlayPipelines.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("missing overlay shader " + path);
            byte[] bytes = input.readAllBytes();
            return MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
