package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayDeque;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Submitted native cloud transmittance fields retained independently by world environment revisions. */
public final class CloudlySurfaceShadow implements AutoCloseable {
    public static final String PROGRAM = "62-surface-shadow";
    public static final int SIZE = 64;
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final ArrayDeque<Image> available = new ArrayDeque<>();
    private Snapshot latest;
    private boolean closed;

    CloudlySurfaceShadow(GpuDevice gpu, ResourceFactory resources) {
        this.gpu = gpu;
        this.resources = resources;
    }

    /** The caller receives a separate claim; publication occurs only after the producing frame is accepted. */
    public synchronized Snapshot capture() { return latest == null ? null : latest.retain(); }

    Snapshot acquire(PassFrame frame, float[] sourceOrigin, double terrainDistance, double[] sourceLight) {
        Image image;
        synchronized (this) { image = available.pollFirst(); }
        if (image == null) image = createImage();
        Image acquired = image;
        ResourceOwner owner = resources.create(() -> recycle(acquired));
        Grid grid = grid(sourceOrigin, terrainDistance);
        Snapshot result = new Snapshot(frame.frameIndex(), image, grid,
                (float) sourceLight[0], (float) sourceLight[2], (float) -sourceLight[1], owner);
        frame.retain(owner);
        frame.whenRetired(result::close);
        prepareWrite(frame, image);
        return result;
    }

    synchronized void publish(Snapshot snapshot) {
        if (closed || latest != null && latest.frameIndex >= snapshot.frameIndex) return;
        Snapshot previous = latest;
        latest = snapshot.retain();
        if (previous != null) previous.close();
    }

    private synchronized void recycle(Image image) {
        if (closed) image.close(); else available.addLast(image);
    }

    /** Texel centers remain fixed in source world metres while the camera moves inside a texel. */
    static Grid grid(float[] origin, double terrainDistance) {
        float horizontal = (float) Math.max(4096, terrainDistance * 2 + 256);
        float vertical = 4096;
        return new Grid(snappedMinimum(origin[0], horizontal), snappedMinimum(origin[1], horizontal),
                snappedMinimum(origin[2], vertical), horizontal, horizontal, vertical);
    }

    private static float snappedMinimum(float center, float extent) {
        float texel = extent / SIZE;
        return (float) (Math.floor(center / texel) * texel - extent * .5);
    }

    private Image createImage() {
        VmaImageAllocation allocation = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var create = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_3D)
                    .format(VK_FORMAT_R16G16B16A16_SFLOAT).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(SIZE, SIZE, SIZE);
            allocation = VmaImageAllocation.create(gpu, create, "Cloudly native surface transmittance");
            descriptors = gpu.descriptorHeap().allocateResources(2);
            var view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(allocation.image())
                    .viewType(VK_IMAGE_VIEW_TYPE_3D).format(VK_FORMAT_R16G16B16A16_SFLOAT);
            view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            var info = VkImageDescriptorInfoEXT.calloc(stack).sType$Default().pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
            gpu.descriptorHeap().writer().writeImages(descriptors, 0, List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, info),
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.STORAGE, info)));
            return new Image(allocation, descriptors);
        } catch (RuntimeException | Error failure) {
            var allocated = allocation;
            var range = descriptors;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (range != null) range.destroy(); },
                    () -> { if (allocated != null) allocated.close(); });
            throw failure;
        }
    }

    private static void prepareWrite(PassFrame frame, Image image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image.allocation.image());
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (latest != null) { latest.close(); latest = null; }
        new ResourceLifetime(available.stream().<Runnable>map(image -> image::close).toArray(Runnable[]::new)).close();
        available.clear();
    }

    public record Grid(float minX, float minY, float minZ, float width, float height, float depth) { }

    public static final class Snapshot implements AutoCloseable {
        private final long frameIndex;
        private final Image image;
        private final Grid grid;
        private final float lightX, lightY, lightZ;
        private final ResourceOwner owner;

        private Snapshot(long frameIndex, Image image, Grid grid, float lightX, float lightY, float lightZ, ResourceOwner owner) {
            this.frameIndex = frameIndex; this.image = image; this.grid = grid;
            this.lightX = lightX; this.lightY = lightY; this.lightZ = lightZ; this.owner = owner;
        }

        public Grid grid() { return grid; }
        public float lightX() { return lightX; }
        public float lightY() { return lightY; }
        public float lightZ() { return lightZ; }
        public int sampledIndex() { return image.descriptors.firstIndex().value(); }
        GpuDescriptorIndex.Resource storage() { return new GpuDescriptorIndex.Resource(sampledIndex() + 1); }
        Snapshot retain() { return new Snapshot(frameIndex, image, grid, lightX, lightY, lightZ, owner.retain()); }

        /** Both passes use the frame queue; an acquire barrier orders the preceding submitted compute write. */
        public void prepareRead(PassFrame frame) {
            frame.retain(owner);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.allocation.image());
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
            }
        }

        public ResourceOwner owner() { return owner; }
        @Override public void close() { owner.close(); }
    }

    private record Image(VmaImageAllocation allocation, GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors) {
        void close() { new ResourceLifetime(descriptors::destroy, allocation::close).close(); }
    }
}
