/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.cloudly.api.pass.PassFrame;
import dev.comfyfluffy.cloudly.api.pass.PostEffectFrame;
import dev.comfyfluffy.cloudly.api.resource.ResourceFactory;
import dev.comfyfluffy.cloudly.api.resource.ResourceOwner;
import dev.comfyfluffy.cloudly.api.vulkan.*;
import dev.comfyfluffy.cloudly.vulkan.ResourceLifetime;
import dev.comfyfluffy.cloudly.vulkan.VmaImageAllocation;
import dev.comfyfluffy.cloudly.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Original Allocate/Fill/Render catalogue programs, with GPU-only bucket offsets.
 * Infinite-distance catalogue emission is retained as six original perspective renders;
 * clouds and atmosphere are traced separately along every real host ray.
 */
public final class CloudlyStars implements AutoCloseable {
    private static final Set<String> PROGRAMS = Set.of("stars-106", "stars-99", "stars-117", "stars-prefix");
    private static final int HASH_BUCKETS = 4096;
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final CloudlyShaderLibrary library;
    private final ResourceOwner owner;
    private final Configuration configuration;
    private final List<Scratch> scratch = new ArrayList<>();
    private CloudlyFramePrograms programs;
    private CloudlySourceBuffer catalogue;
    private VmaImageAllocation cube;
    private GpuDescriptorRange<GpuDescriptorIndex.Resource> cubeViews;
    private Scratch counts, first, coordinates, values;
    private CameraImage cameraImage;
    private boolean baked, bakeRecorded, closed;

    public static CloudlyStars create(GpuDevice gpu, ResourceFactory resources,
                                      CloudlyShaderLibrary library, Path manifest) throws IOException {
        return new CloudlyStars(gpu, resources, library, Configuration.load(manifest));
    }

    private CloudlyStars(GpuDevice gpu, ResourceFactory resources, CloudlyShaderLibrary library,
                         Configuration configuration) {
        this.gpu = gpu;
        this.resources = resources;
        this.library = library;
        this.configuration = configuration;
        owner = resources.create(this::destroy);
        try {
            programs = new CloudlyFramePrograms(gpu, resources, library, PROGRAMS);
            catalogue = CloudlySourceBuffer.upload(gpu, resources, configuration.catalogue(), 16, List.of());
            counts = allocateScratch(HASH_BUCKETS * 4L);
            first = allocateScratch(HASH_BUCKETS * 4L);
            int diameter = 2 * (int)Math.ceil(configuration.radius()) + 1;
            long capacity = Math.multiplyExact((long)configuration.starCount(), Math.multiplyExact(diameter, diameter));
            coordinates = allocateScratch(Math.multiplyExact(capacity, 4L));
            values = allocateScratch(Math.multiplyExact(capacity, 4L));
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkImageCreateInfo.calloc(stack).sType$Default()
                        .flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT).imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_R16G16B16A16_SFLOAT).mipLevels(1).arrayLayers(6)
                        .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(configuration.resolution(), configuration.resolution(), 1);
                cube = VmaImageAllocation.create(gpu, info, "Cloudly original star catalogue");
                cubeViews = gpu.descriptorHeap().allocateResources(7);
                var views = VkImageViewCreateInfo.calloc(7, stack);
                List<GpuDescriptorWriter.ImageWrite> writes = new ArrayList<>();
                for (int index = 0; index < 7; index++) {
                    var view = views.get(index).sType$Default().image(cube.image())
                            .viewType(index == 0 ? VK_IMAGE_VIEW_TYPE_CUBE : VK_IMAGE_VIEW_TYPE_2D)
                            .format(VK_FORMAT_R16G16B16A16_SFLOAT);
                    view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(index == 0 ? 0 : index - 1).layerCount(index == 0 ? 6 : 1);
                    writes.add(new GpuDescriptorWriter.ImageWrite(index == 0 ? GpuImageDescriptorKind.SAMPLED : GpuImageDescriptorKind.STORAGE,
                            new ImageDescriptor(view, VK_IMAGE_LAYOUT_GENERAL)));
                }
                gpu.descriptorHeap().writer().writeImages(cubeViews, 0, writes);
            }
        } catch (IOException failure) {
            ResourceLifetime.closeAfterFailure(failure, owner::close);
            throw new java.io.UncheckedIOException(failure);
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, owner::close);
            throw failure;
        }
    }

    public GpuDescriptorIndex.Resource cubeDescriptor() {
        if (closed) throw new IllegalStateException("Star catalogue is closed");
        return cubeViews.firstIndex();
    }

    /** The caller retains this claim whenever the catalogue descriptor is reachable. */
    public ResourceOwner owner() { return owner; }

    public GpuDescriptorIndex.Resource cameraDescriptor() {
        if (closed || cameraImage == null) throw new IllegalStateException("Camera star catalogue is not prepared");
        return cameraImage.descriptors().firstIndex();
    }

    /**
     * Current authored scenes have zero scintillation, so catalogue radiance is baked once.
     * World time rotates the infinite field at the emitter query, without camera-relative caches.
     */
    public synchronized void record(PostEffectFrame frame, CloudlyFrameCamera camera, float rotationRadians) {
        if (closed) throw new IllegalStateException("Star catalogue is closed");
        frame.retain(owner);
        if (!baked && !bakeRecorded) {
            initialize(frame);
            for (int face = 0; face < 6; face++) {
                render(frame, faceCamera(face), configuration.resolution(), configuration.resolution(),
                        new GpuDescriptorIndex.Resource(cubeViews.firstIndex().value() + face + 1), 0);
            }
            bakeRecorded = true;
            frame.whenSubmitted(() -> { synchronized (this) { baked = true; bakeRecorded = false; } });
            frame.whenRetired(() -> { synchronized (this) { if (!baked) bakeRecorded = false; } });
        }
        int width = Math.max(1, (int)Math.ceil(frame.sceneColor().width() * configuration.screenFraction()));
        int height = Math.max(1, (int)Math.ceil(frame.sceneColor().height() * configuration.screenFraction()));
        if (cameraImage == null || cameraImage.width() != width || cameraImage.height() != height) {
            CameraImage previous = cameraImage;
            cameraImage = allocateCameraImage(width, height);
            if (previous != null) previous.owner().close();
        }
        frame.retain(cameraImage.owner());
        clearCamera(frame);
        render(frame, camera, width, height,
                new GpuDescriptorIndex.Resource(cameraImage.descriptors().firstIndex().value() + 1), rotationRadians);
    }

    private void render(PassFrame frame, CloudlyFrameCamera camera, int width, int height,
                        GpuDescriptorIndex.Resource target, float rotationRadians) {
        clearCounts(frame);
            programs.record(frame, "stars-106", constants("stars-106", camera, width, height, rotationRadians),
                    Map.of("StarsDataBuffer", catalogue.descriptor(), "RWStarsListSize", counts.writeTexels()),
                    Map.of(), camera, List.of(owner), Math.ceilDiv(configuration.starCount(), 32), 1, 1);
            programs.record(frame, "stars-prefix", constants("stars-prefix", camera, width, height, rotationRadians),
                    Map.of("Counts", counts.structured(), "First", first.structured()),
                    Map.of(), camera, List.of(owner), 1, 1, 1);
            programs.record(frame, "stars-99", constants("stars-99", camera, width, height, rotationRadians),
                    Map.of("StarsDataBuffer", catalogue.descriptor(), "RWStarsListSize", counts.writeTexels(),
                            "RWStarsPixelCoords", coordinates.writeTexels(), "RWStarsPixelValues", values.writeTexels(),
                            "StarsListFirst", first.readTexels()),
                    Map.of(), camera, List.of(owner), Math.ceilDiv(configuration.starCount(), 32), 1, 1);
            programs.record(frame, "stars-117", constants("stars-117", camera, width, height, rotationRadians),
                    Map.of("RWStarsTexture2D", target,
                            "StarsListFirst", first.readTexels(), "StarsListSize", counts.readTexels(),
                            "StarsPixelCoords", coordinates.readTexels(), "StarsPixelValues", values.readTexels()),
                    Map.of(), camera, List.of(owner), HASH_BUCKETS / 32, 1, 1);
    }

    private ByteBuffer constants(String id, CloudlyFrameCamera camera, int width, int height, float rotationRadians) {
        var program = library.program(id);
        var layout = program.parametersLayout();
        ByteBuffer result = ByteBuffer.allocate(program.parametersByteSize()).order(ByteOrder.LITTLE_ENDIAN);
        if (id.equals("stars-prefix")) {
            layout.putInts(result, "Count", HASH_BUCKETS);
            return result;
        }
        if (!id.equals("stars-117")) {
            CloudlyFrameUniforms.writeView(layout, result, camera, camera,
                    width, height, 0, 0, 0, 0, 1, 0, 0, 0);
            layout.putInts(result, "StarsDataNum", configuration.starCount());
            layout.putFloats(result, "StarsRadius", configuration.radius());
            float[] rotation = configuration.rotation().clone();
            rotation[2] -= rotationRadians;
            layout.putFloats(result, "StarsGlobalRotation", rotation);
        }
        layout.putInts(result, "StarsTexture2DSize", width, height);
        layout.putInts(result, "StarsHashN", HASH_BUCKETS);
        if (id.equals("stars-99")) {
            layout.putFloats(result, "StarsIntensity", configuration.intensity());
            layout.putFloats(result, "StarsFadePower", configuration.fadePower());
            layout.putFloats(result, "StarsScintillationsPower", 0);
            layout.putFloats(result, "StarsAnimTime", 0);
        } else if (id.equals("stars-117")) {
            layout.putInts(result, "StarsNoColorMode", configuration.noColor() ? 1 : 0);
        }
        return result;
    }

    private void initialize(PassFrame frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var image = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().image(cube.image())
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT).dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            image.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(6);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(image));
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(6);
            vkCmdClearColorImage(frame.commandBuffer(), cube.image(), VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack), range);
            memoryBarrier(frame, VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT | VK13.VK_PIPELINE_STAGE_2_HOST_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT | VK13.VK_ACCESS_2_HOST_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
        }
    }

    private void clearCounts(PassFrame frame) {
        memoryBarrier(frame, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT, VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
        vkCmdFillBuffer(frame.commandBuffer(), counts.buffer().buffer(), 0, counts.buffer().byteSize(), 0);
        memoryBarrier(frame, VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT, VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
    }

    private CameraImage allocateCameraImage(int width, int height) {
        VmaImageAllocation image = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> views = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R16G16B16A16_SFLOAT).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            image = VmaImageAllocation.create(gpu, info, "Cloudly original camera star catalogue");
            views = gpu.descriptorHeap().allocateResources(2);
            var view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.image())
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R16G16B16A16_SFLOAT);
            view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            gpu.descriptorHeap().writer().writeImages(views, 0, List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, new ImageDescriptor(view, VK_IMAGE_LAYOUT_GENERAL)),
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.STORAGE, new ImageDescriptor(view, VK_IMAGE_LAYOUT_GENERAL))));
            var ownedViews = views;
            var ownedImage = image;
            return new CameraImage(width, height, image, views,
                    resources.create(() -> new ResourceLifetime(ownedViews::destroy, ownedImage::close).close()));
        } catch (RuntimeException | Error failure) {
            var ownedViews = views;
            var ownedImage = image;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (ownedViews != null) ownedViews.destroy(); },
                    () -> { if (ownedImage != null) ownedImage.close(); });
            throw failure;
        }
    }

    private void clearCamera(PassFrame frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().image(cameraImage.image().image())
                    .oldLayout(cameraImage.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcStageMask(cameraImage.initialized ? VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT : VK13.VK_PIPELINE_STAGE_2_NONE)
                    .srcAccessMask(cameraImage.initialized ? VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT : 0)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT).dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdClearColorImage(frame.commandBuffer(), cameraImage.image().image(), VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack), range);
            memoryBarrier(frame, VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT, VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
            CameraImage initializedImage = cameraImage;
            frame.whenSubmitted(() -> initializedImage.initialized = true);
        }
    }

    private static final class CameraImage {
        private final int width, height;
        private final VmaImageAllocation image;
        private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
        private final ResourceOwner owner;
        private boolean initialized;
        CameraImage(int width, int height, VmaImageAllocation image, GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors, ResourceOwner owner) {
            this.width = width; this.height = height; this.image = image; this.descriptors = descriptors; this.owner = owner;
        }
        int width() { return width; }
        int height() { return height; }
        VmaImageAllocation image() { return image; }
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors() { return descriptors; }
        ResourceOwner owner() { return owner; }
    }

    private static void memoryBarrier(PassFrame frame, long sourceStage, long sourceAccess, long targetStage, long targetAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                    .dstStageMask(targetStage).dstAccessMask(targetAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    private Scratch allocateScratch(long bytes) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(gpu, bytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT, "Cloudly original stars scratch");
        GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
        try {
            range = gpu.descriptorHeap().allocateResources(3);
            long address = buffer.deviceRange().address().value();
            gpu.descriptorHeap().writer().writeResource(range, 0,
                    ResourceDescriptor.texels(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, address, bytes, VK_FORMAT_R32_UINT));
            gpu.descriptorHeap().writer().writeResource(range, 1,
                    ResourceDescriptor.texels(VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER, address, bytes, VK_FORMAT_R32_UINT));
            gpu.descriptorHeap().writer().writeResource(range, 2,
                    ResourceDescriptor.buffer(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, address, bytes));
            Scratch result = new Scratch(buffer, range);
            scratch.add(result);
            return result;
        } catch (RuntimeException | Error failure) {
            var allocation = range;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (allocation != null) allocation.destroy(); }, buffer::close);
            throw failure;
        }
    }

    private record Scratch(VmaMappedBuffer buffer, GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors) implements AutoCloseable {
        GpuDescriptorIndex.Resource readTexels() { return descriptors.firstIndex(); }
        GpuDescriptorIndex.Resource writeTexels() { return new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + 1); }
        GpuDescriptorIndex.Resource structured() { return new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + 2); }
        @Override public void close() { new ResourceLifetime(descriptors::destroy, buffer::close).close(); }
    }

    /** Standard Vulkan cube face directions, converted to the host inverse-projection convention. */
    private static CloudlyFrameCamera faceCamera(int face) {
        float[][] forward = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        float[][] right = {{0,0,-1},{0,0,1},{1,0,0},{1,0,0},{1,0,0},{-1,0,0}};
        float[] f = forward[face], r = right[face];
        float[] u = {f[1]*r[2]-f[2]*r[1], f[2]*r[0]-f[0]*r[2], f[0]*r[1]-f[1]*r[0]};
        float[] inverse = {r[0],r[2],-r[1],0, -u[0],-u[2],u[1],0,
                0,0,0,1, f[0],f[2],-f[1],0};
        return new CloudlyFrameCamera(inverse, 0, 0, 0, 0, 1);
    }

    private record Configuration(ByteBuffer catalogue, int starCount, int resolution, float intensity,
                                 float radius, float fadePower, float[] rotation, boolean noColor, float screenFraction) {
        static Configuration load(Path manifest) throws IOException {
            Path actual = manifest.toRealPath();
            JsonObject root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
            int count = root.get("starCount").getAsInt(), resolution = root.get("faceResolution").getAsInt();
            float radius = root.get("radius").getAsFloat();
            float fraction = root.get("screenFraction").getAsFloat();
            if (root.get("schemaVersion").getAsInt() != 1 || root.get("hashBuckets").getAsInt() != HASH_BUCKETS
                    || root.get("scintillationsPower").getAsFloat() != 0 || count < 1 || count > 100000
                    || resolution < 256 || resolution > 4096 || !Float.isFinite(radius) || radius <= 0 || radius > 8
                    || !Float.isFinite(fraction) || fraction <= 0 || fraction > 1) {
                throw new IOException("Unsupported original star catalogue recipe");
            }
            Path payload = actual.getParent().resolve(root.get("catalogue").getAsString()).normalize();
            if (!payload.startsWith(actual.getParent())) throw new IOException("Star catalogue leaves its manifest");
            byte[] bytes = Files.readAllBytes(payload);
            if (bytes.length != Math.multiplyExact(count, 16)) throw new IOException("Incomplete original star catalogue");
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(root.get("sha256").getAsString())) throw new IOException("Original star catalogue digest mismatch");
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            float[] rotation = new float[3];
            for (int index = 0; index < 3; index++) rotation[index] = root.getAsJsonArray("globalRotation").get(index).getAsFloat();
            return new Configuration(ByteBuffer.wrap(bytes), count, resolution, root.get("intensity").getAsFloat(), radius,
                    root.get("fadePower").getAsFloat(), rotation, root.get("noColorMode").getAsBoolean(), root.get("screenFraction").getAsFloat());
        }
    }

    private void destroy() {
        List<Runnable> cleanup = new ArrayList<>();
        if (cubeViews != null) cleanup.add(cubeViews::destroy);
        if (cube != null) cleanup.add(cube::close);
        scratch.forEach(buffer -> cleanup.add(buffer::close));
        if (catalogue != null) cleanup.add(catalogue::close);
        if (cameraImage != null) cleanup.add(cameraImage.owner()::close);
        if (programs != null) cleanup.add(programs::close);
        new ResourceLifetime(cleanup.toArray(Runnable[]::new)).close();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        owner.close();
    }
}
