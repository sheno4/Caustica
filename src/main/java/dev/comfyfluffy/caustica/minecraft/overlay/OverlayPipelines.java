package dev.comfyfluffy.caustica.minecraft.overlay;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.api.gpu.*;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** Shader-object creation and immutable descriptor entries shared by Minecraft overlay features. */
final class OverlayPipelines {
    enum VertexFormat { NONE, POSITION, POSITION_TEX_COLOR }
    enum Blend { NONE, ALPHA }

    static final class Pipeline implements AutoCloseable {
        private final ShaderObjectGraphics shader;
        Pipeline(ShaderObjectGraphics shader) { this.shader = shader; }
        void bind(VkCommandBuffer commandBuffer, ByteBuffer pushData, int width, int height) {
            shader.bind(commandBuffer, pushData, width, height);
        }
        @Override public void close() { shader.close(); }
    }

    static final class Spec {
        private final String vertex, fragment;
        private VertexFormat format = VertexFormat.NONE;
        private int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private Blend blend = Blend.NONE;
        private int samples = VK_SAMPLE_COUNT_1_BIT;
        Spec(String vertex, String fragment) { this.vertex = vertex; this.fragment = fragment; }
        Spec vertex(VertexFormat value) { format = value; return this; }
        Spec topology(int value) { topology = value; return this; }
        Spec blend(Blend value) { blend = value; return this; }
        Spec attachment(int ignored) { return this; }
        Spec samples(int value) { samples = value; return this; }
        Spec push(int ignored, int ignoredStages) { return this; }
        Pipeline build(GpuDevice gpu, String label) {
            ByteBuffer vertexCode = load(vertex);
            ByteBuffer fragmentCode = load(fragment);
            try {
                return new Pipeline(ShaderObjectGraphics.create(gpu, vertexCode, fragmentCode,
                        switch (format) {
                            case NONE -> ShaderObjectGraphics.VertexFormat.NONE;
                            case POSITION -> ShaderObjectGraphics.VertexFormat.POSITION;
                            case POSITION_TEX_COLOR -> ShaderObjectGraphics.VertexFormat.POSITION_TEX_COLOR;
                        }, topology, blend == Blend.ALPHA ? ShaderObjectGraphics.Blend.ALPHA
                                : ShaderObjectGraphics.Blend.NONE, samples));
            } finally {
                MemoryUtil.memFree(fragmentCode);
                MemoryUtil.memFree(vertexCode);
            }
        }
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
        static FontImage create(GpuDevice gpu, VulkanGpuTextureView view, String label) {
            VulkanGpuTexture texture = view.texture();
            if (texture.getFormat() != GpuFormat.RGBA8_UNORM) {
                throw new IllegalArgumentException("font atlas must be RGBA8_UNORM");
            }
            texture.addViews();
            GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                descriptor = gpu.descriptorHeap().allocateResources(1, label);
                GpuDescriptorRange<GpuDescriptorIndex.Resource> allocated = descriptor;
                VkImageViewCreateInfo imageView = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(texture.vkImage()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
                imageView.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(view.baseMipLevel()).levelCount(view.mipLevels())
                        .baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(imageView).layout(VK_IMAGE_LAYOUT_GENERAL);
                gpu.descriptorHeap().writer().writeResource(allocated, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(image)));
                return new FontImage(texture, allocated);
            } catch (RuntimeException | Error failure) {
                if (descriptor != null) descriptor.destroy();
                texture.removeViews();
                throw failure;
            }
        }
        int index() { return descriptor.firstIndex().value(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { descriptor.destroy(); } finally { texture.removeViews(); }
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
