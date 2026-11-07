package dev.comfyfluffy.caustica.rt.cloudly;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Complete native environment revisions retain their cube images through every consuming frame. */
public final class CloudlyEnvironment implements AutoCloseable {
    public static final int SIZE = 1024;
    private static final int FACES = 6;
    private static final int FORMAT = VK_FORMAT_R32G32B32A32_SFLOAT;
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final ShaderObjectCompute copy;
    private final ResourceOwner copyOwner;
    private final ArrayDeque<Pair> available = new ArrayDeque<>();
    private boolean closed;

    public CloudlyEnvironment(GpuDevice gpu, ResourceFactory resources) {
        this.gpu = gpu;
        this.resources = resources;
        requireCubeFormat();
        copy = loadCopyShader();
        try { copyOwner = resources.create(copy::close); }
        catch (RuntimeException | Error failure) { copy.close(); throw failure; }
    }

    public Capture acquire() {
        Pair pair;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Cloudly environment is closed");
            pair = available.pollFirst();
        }
        if (pair == null) pair = createPair();
        synchronized (this) {
            if (closed) { pair.close(); throw new IllegalStateException("Cloudly environment is closed"); }
        }
        Pair acquired = pair;
        try { return new Capture(acquired, resources.create(() -> recycle(acquired))); }
        catch (RuntimeException | Error failure) { recycle(acquired); throw failure; }
    }

    private synchronized void recycle(Pair pair) {
        if (closed || available.size() >= 2) pair.close(); else available.addLast(pair);
    }

    private ShaderObjectCompute loadCopyShader() {
        String path = "/caustica/shaders/pipelines/cloudly/environment_copy.comp.spv";
        try (var input = CloudlyEnvironment.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing SPIR-V resource " + path);
            byte[] code = input.readAllBytes();
            ByteBuffer bytes = MemoryUtil.memAlloc(code.length);
            try {
                bytes.put(code).flip();
                return ShaderObjectCompute.create(gpu, bytes, "main", 20, List.of(
                        new ShaderObjectCompute.Binding("0", 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                        new ShaderObjectCompute.Binding("1", 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                        new ShaderObjectCompute.Binding("2", 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)));
            } finally { MemoryUtil.memFree(bytes); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void requireCubeFormat() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var features = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(gpu.vk().getPhysicalDevice(), FORMAT, features);
            int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((features.optimalTilingFeatures() & required) != required) {
                throw new UnsupportedOperationException("Original environment requires linearly sampled RGBA32F storage images");
            }
            var properties = VkImageFormatProperties.calloc(stack);
            int status = vkGetPhysicalDeviceImageFormatProperties(gpu.vk().getPhysicalDevice(), FORMAT,
                    VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                    VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT, properties);
            if (status != VK_SUCCESS || properties.maxArrayLayers() < FACES
                    || properties.maxExtent().width() < SIZE || properties.maxExtent().height() < SIZE) {
                throw new UnsupportedOperationException("Original environment cube dimensions or usage are unsupported");
            }
        }
    }

    private Pair createPair() {
        Image environment = createImage("Cloudly native environment");
        try { return new Pair(environment, createImage("Cloudly native celestial contribution")); }
        catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, environment::close);
            throw failure;
        }
    }

    private Image createImage(String label) {
        VmaImageAllocation allocation = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var create = VkImageCreateInfo.calloc(stack).sType$Default().flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)
                    .imageType(VK_IMAGE_TYPE_2D).format(FORMAT).mipLevels(1).arrayLayers(FACES)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(SIZE, SIZE, 1);
            allocation = VmaImageAllocation.create(gpu, create, label);
            descriptors = gpu.descriptorHeap().allocateResources(2);
            var view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(allocation.image())
                    .viewType(VK_IMAGE_VIEW_TYPE_CUBE).format(FORMAT);
            view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(FACES);
            var info = VkImageDescriptorInfoEXT.calloc(stack).sType$Default().pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
            gpu.descriptorHeap().writer().writeImages(descriptors, 0, List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, info)));
            view.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY);
            gpu.descriptorHeap().writer().writeImages(descriptors, 1, List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.STORAGE, info)));
            long cubeView = gpu.descriptorHeap().imageView(descriptors.firstIndex());
            return new Image(allocation, descriptors, cubeView);
        } catch (RuntimeException | Error failure) {
            var allocated = allocation;
            var range = descriptors;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (range != null) range.destroy(); },
                    () -> { if (allocated != null) allocated.close(); });
            throw failure;
        }
    }

    private static void barrier(PassFrame frame, Pair pair, boolean initialize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barriers = VkImageMemoryBarrier2.calloc(2, stack);
            Image[] images = {pair.environment, pair.celestial};
            for (int i = 0; i < images.length; i++) {
                var barrier = barriers.get(i).sType$Default()
                        .srcStageMask(initialize ? VK13.VK_PIPELINE_STAGE_2_NONE : VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                        .srcAccessMask(initialize ? VK13.VK_ACCESS_2_NONE : VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                                | KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                                | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(initialize ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(images[i].allocation.image());
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(FACES);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(frame.commandBuffer(),
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        new ResourceLifetime(available.stream().<Runnable>map(pair -> pair::close).toArray(Runnable[]::new)).close();
        available.clear();
        copyOwner.close();
    }

    /** Exclusive writer for one unpublished six-face revision. */
    public final class Capture implements AutoCloseable {
        private final Pair pair;
        private final ResourceOwner owner;
        private boolean prepared, released;

        private Capture(Pair pair, ResourceOwner owner) { this.pair = pair; this.owner = owner; }

        /** Source is the native 1024-square, unexposed linear BT.709 scene colour for this face. */
        public void recordFace(PassFrame frame, GpuDescriptorIndex.Resource source, int face, boolean withCelestials) {
            if (released) throw new IllegalStateException("Environment capture is closed");
            synchronized (CloudlyEnvironment.this) {
                if (closed) throw new IllegalStateException("Cloudly environment is closed");
            }
            if (face < 0 || face >= FACES) throw new IllegalArgumentException("Cube face is out of range");
            frame.retain(owner);
            frame.retain(copyOwner);
            if (!prepared) {
                barrier(frame, pair, !pair.initialized);
                frame.whenSubmitted(() -> pair.initialized = true);
                prepared = true;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var push = stack.malloc(20).order(ByteOrder.LITTLE_ENDIAN);
                push.putInt(source.value()).putInt(pair.environment.storage().value()).putInt(pair.celestial.storage().value())
                        .putInt(face).putInt(withCelestials ? 1 : 0).flip();
                frame.whenRetired(copy.dispatch(frame.commandBuffer(), push, (SIZE + 7) / 8, (SIZE + 7) / 8, 1)::close);
            }
            barrier(frame, pair, false);
        }

        /** The caller publishes this independent claim only after all twelve source face draws are submitted. */
        public Snapshot snapshot() {
            if (released) throw new IllegalStateException("Environment capture is closed");
            return new Snapshot(pair, owner.retain());
        }

        @Override public void close() {
            if (released) return;
            released = true;
            owner.close();
        }
    }

    public static final class Snapshot implements AutoCloseable {
        private final Pair pair;
        private final ResourceOwner owner;

        private Snapshot(Pair pair, ResourceOwner owner) { this.pair = pair; this.owner = owner; }

        public long environmentView() { return pair.environment.cubeView; }
        public long celestialView() { return pair.celestial.cubeView; }
        public ResourceOwner owner() { return owner; }
        public Snapshot retain() { return new Snapshot(pair, owner.retain()); }

        /** Orders the submitted producer and protects both sampled views through this consuming frame. */
        public void prepareRead(PassFrame frame) {
            frame.retain(owner);
            barrier(frame, pair, false);
        }

        @Override public void close() { owner.close(); }
    }

    private static final class Pair {
        final Image environment, celestial;
        volatile boolean initialized;
        Pair(Image environment, Image celestial) { this.environment = environment; this.celestial = celestial; }
        void close() { new ResourceLifetime(environment::close, celestial::close).close(); }
    }

    private record Image(VmaImageAllocation allocation, GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                         long cubeView) {
        GpuDescriptorIndex.Resource storage() { return new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + 1); }
        void close() { new ResourceLifetime(descriptors::destroy, allocation::close).close(); }
    }
}
