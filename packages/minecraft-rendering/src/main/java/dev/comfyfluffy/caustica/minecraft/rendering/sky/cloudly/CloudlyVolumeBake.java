package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkFormatProperties2;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageFormatProperties2;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;
import static org.lwjgl.vulkan.VK10.*;

/** One-time execution of private noise and SDF programs with explicitly supplied source data. */
public final class CloudlyVolumeBake {
    private static final int USAGE = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private CloudlyVolumeBake() { }

    /** Raw Vulkan clear words preserve the caller's floating-point or integer format interpretation. */
    public record ClearValue(int x, int y, int z, int w) {
        public static ClearValue floats(float x, float y, float z, float w) {
            return new ClearValue(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y),
                    Float.floatToRawIntBits(z), Float.floatToRawIntBits(w));
        }
    }

    /** Dimensions, formats, mip counts and filtering requirements come from the private source plan. */
    public enum Dimension {
        TWO_D(VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_2D, 1),
        THREE_D(VK_IMAGE_TYPE_3D, VK_IMAGE_VIEW_TYPE_3D, 1),
        CUBE(VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_CUBE, 6);
        private final int imageType, viewType, layers;
        Dimension(int imageType, int viewType, int layers) { this.imageType = imageType; this.viewType = viewType; this.layers = layers; }
    }
    public record ImageSpec(String name, Dimension dimension, int width, int height, int depth,
                            int mipLevels, int format, boolean linearFiltering, boolean publish, ClearValue clear) {
        public ImageSpec {
            if (dimension == null || width < 1 || height < 1 || depth < 1 || mipLevels < 1
                    || dimension != Dimension.THREE_D && depth != 1
                    || dimension == Dimension.CUBE && width != height) {
                throw new IllegalArgumentException("Invalid original bake texture dimensions: " + name);
            }
        }
    }
    public record SamplerSpec(String name, int magFilter, int minFilter, int mipmapMode,
                              int addressU, int addressV, int addressW, float minLod, float maxLod) { }

    /**
     * Complete parameter bytes are frozen here. The binding and push factories only resolve already
     * allocated descriptors and addresses; they supply every field required by the private program ABI.
     */
    public sealed interface StageSpec permits DispatchSpec, CopySpec { }

    /** Same-format copies preserve the quantized original before a separable filter changes it. */
    public record CopySpec(String source, int sourceMip, String destination, int destinationMip) implements StageSpec { }

    public record DispatchSpec(String programId, ByteBuffer constants,
                               Function<View, ByteBuffer> bindings,
                               BiFunction<View, CloudlyShaderLibrary.Parameters, Map<String, Long>> push,
                               int groupsX, int groupsY, int groupsZ) implements StageSpec {
        public DispatchSpec {
            ByteBuffer frozen = ByteBuffer.allocate(constants.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            frozen.put(constants.duplicate()).flip();
            constants = frozen.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /** Input owners are borrowed; the accepted job holds independent references until completion. */
    public record Plan(List<ImageSpec> images, List<SamplerSpec> samplers,
                       List<StageSpec> stages, List<ResourceOwner> inputs) {
        public Plan {
            images = List.copyOf(images); samplers = List.copyOf(samplers);
            stages = List.copyOf(stages); inputs = List.copyOf(inputs);
            var names = new java.util.HashSet<String>();
            for (ImageSpec image : images) if (!names.add(image.name())) throw new IllegalArgumentException("Duplicate bake image " + image.name());
            Map<String, ImageSpec> byName = new LinkedHashMap<>();
            images.forEach(image -> byName.put(image.name(), image));
            for (StageSpec stage : stages) if (stage instanceof CopySpec copy) validateCopy(copy, byName);
        }
    }

    public record Texture(Dimension dimension, int width, int height, int depth, int mipLevels, int format, long image,
                          GpuDescriptorIndex.Resource sampledIndex, List<GpuDescriptorIndex.Resource> storageIndices,
                          List<GpuDescriptorIndex.Resource> mipSampledIndices) {
        public Texture { storageIndices = List.copyOf(storageIndices); mipSampledIndices = List.copyOf(mipSampledIndices); }
        public GpuDescriptorIndex.Resource storageIndex(int mip) { return storageIndices.get(mip); }
        public GpuDescriptorIndex.Resource sampledIndex(int mip) { return mip < 0 ? sampledIndex : mipSampledIndices.get(mip); }
    }
    public record View(Map<String, Texture> images, Map<String, GpuDescriptorIndex.Sampler> samplers) {
        public View { images = Map.copyOf(images); samplers = Map.copyOf(samplers); }
        public Texture image(String name) { return images.get(name); }
        public GpuDescriptorIndex.Sampler sampler(String name) { return samplers.get(name); }
    }

    /** Immutable completed outputs; scratch textures and dispatch state have separate ownership. */
    public record Ready(View outputs, ResourceOwner owner) implements Completion, AutoCloseable {
        public ResourceOwner retain() { return owner.retain(); }
        @Override public void close() { owner.close(); }
    }
    public sealed interface Completion permits Ready, Failed, Cancelled { }
    public record Failed(Throwable failure) implements Completion { }
    public record Cancelled() implements Completion { }

    /**
     * Prepares immutable dispatch state on the caller's worker. An accepted job initializes and executes
     * all supplied stages, with read/write dependencies between them. Completion runs once off the caller
     * after GPU completion and transfers a Ready producer claim to the recipient.
     */
    public static GpuComputeJob prepare(GpuDevice gpu, GpuComputeQueue queue, ResourceFactory resources,
                                       CloudlyShaderLibrary library, Plan plan,
                                       Consumer<? super Completion> completion) throws IOException {
        List<OwnedImage> images = new ArrayList<>();
        List<CloudlyShaderLibrary.Instance> shaders = new ArrayList<>();
        List<CloudlyShaderLibrary.Parameters> parameters = new ArrayList<>();
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers = null;
        ResourceOwner outputOwner = null, workOwner = null;
        List<ResourceOwner> acceptedClaims = new ArrayList<>();
        try {
            for (ImageSpec spec : plan.images()) {
                checkInterrupted();
                images.add(allocateImage(gpu, queue, spec));
            }
            Map<String, GpuDescriptorIndex.Sampler> samplerIndices = new LinkedHashMap<>();
            if (!plan.samplers().isEmpty()) {
                samplers = gpu.descriptorHeap().allocateSamplers(plan.samplers().size());
                writeSamplers(gpu, samplers, plan.samplers(), samplerIndices);
            }
            Map<String, Texture> textureIndices = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
            for (OwnedImage image : images) {
                textureIndices.put(image.spec().name(), image.texture());
                if (image.spec().publish()) outputs.put(image.spec().name(), image.texture());
            }
            View view = new View(textureIndices, samplerIndices);
            List<RecordedStage> stages = new ArrayList<>();
            Map<String, CloudlyShaderLibrary.Instance> shaderInstances = new LinkedHashMap<>();
            for (StageSpec stage : plan.stages()) {
                checkInterrupted();
                if (stage instanceof CopySpec copy) {
                    stages.add(new RecordedCopy(view.image(copy.source()), copy.sourceMip(), view.image(copy.destination()), copy.destinationMip()));
                    continue;
                }
                DispatchSpec spec = (DispatchSpec) stage;
                CloudlyShaderLibrary.Instance shader = shaderInstances.get(spec.programId());
                if (shader == null) {
                    shader = library.program(spec.programId()).create(gpu, resources);
                    shaders.add(shader);
                    shaderInstances.put(spec.programId(), shader);
                }
                var parameter = shader.parameters(gpu, resources, spec.constants(), spec.bindings().apply(view));
                parameters.add(parameter);
                stages.add(new RecordedDispatch(shader, Map.copyOf(spec.push().apply(view, parameter)),
                        spec.groupsX(), spec.groupsY(), spec.groupsZ()));
            }
            var samplerAllocation = samplers;
            outputOwner = resources.create(() -> new ResourceLifetime(
                    () -> { if (samplerAllocation != null) samplerAllocation.destroy(); },
                    () -> closeImages(images, true)).close());
            workOwner = resources.create(() -> new ResourceLifetime(
                    () -> closeImages(images, false),
                    () -> closeParameters(parameters),
                    () -> closeShaders(shaders)).close());
            Ready ready = new Ready(new View(outputs, samplerIndices), outputOwner);
            acceptedClaims.add(outputOwner.retain()); acceptedClaims.add(workOwner.retain());
            for (ResourceOwner input : plan.inputs()) acceptedClaims.add(input.retain());
            ResourceOwner preparationClaim = workOwner;
            return queue.submit(commands -> record(commands, images, stages), acceptedClaims, result -> {
                preparationClaim.close();
                if (result instanceof GpuComputeCompletion.Succeeded) {
                    try { completion.accept(ready); }
                    catch (RuntimeException | Error failure) { ready.close(); throw failure; }
                } else {
                    ready.close();
                    completion.accept(result instanceof GpuComputeCompletion.Failed failed
                            ? new Failed(failed.failure()) : new Cancelled());
                }
            });
        } catch (IOException | RuntimeException | Error failure) {
            ResourceOwner allocatedOutput = outputOwner, allocatedWork = workOwner;
            var allocatedSamplers = samplers;
            closeAfterFailure(failure,
                    () -> new ResourceLifetime(acceptedClaims.stream().<Runnable>map(owner -> owner::close).toArray(Runnable[]::new)).close(),
                    () -> {
                        if (allocatedOutput != null) allocatedOutput.close();
                        else new ResourceLifetime(() -> { if (allocatedSamplers != null) allocatedSamplers.destroy(); },
                                () -> closeImages(images, true)).close();
                    },
                    () -> {
                        if (allocatedWork != null) allocatedWork.close();
                        else new ResourceLifetime(() -> closeImages(images, false),
                                () -> closeParameters(parameters), () -> closeShaders(shaders)).close();
                    });
            throw failure;
        }
    }

    private static OwnedImage allocateImage(GpuDevice gpu, GpuComputeQueue queue, ImageSpec spec) {
        checkImageSupport(gpu, spec);
        VmaImageAllocation image = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(spec.dimension().imageType).format(spec.format())
                    .flags(spec.dimension() == Dimension.CUBE ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0)
                    .mipLevels(spec.mipLevels()).arrayLayers(spec.dimension().layers).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(USAGE).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(spec.width(), spec.height(), spec.depth());
            int[] families = queue.sharedQueueFamilyIndices();
            if (families.length > 1) info.sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(families));
            image = VmaImageAllocation.create(gpu, info, "Cloudly original bake " + spec.name());
            int descriptorCount = 2 * spec.mipLevels() + 1;
            descriptors = gpu.descriptorHeap().allocateResources(descriptorCount);
            var views = VkImageViewCreateInfo.calloc(descriptorCount, stack);
            var encoded = VkImageDescriptorInfoEXT.calloc(descriptorCount, stack);
            List<GpuDescriptorWriter.ImageWrite> writes = new ArrayList<>();
            List<GpuDescriptorIndex.Resource> storage = new ArrayList<>();
            List<GpuDescriptorIndex.Resource> mipSampled = new ArrayList<>();
            for (int index = 0; index < descriptorCount; index++) {
                boolean storageView = index > 0 && index <= spec.mipLevels();
                int mip = storageView ? index - 1 : index - 1 - spec.mipLevels();
                int viewType = storageView && spec.dimension() == Dimension.CUBE ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : spec.dimension().viewType;
                var view = views.get(index).sType$Default().image(image.image()).viewType(viewType).format(spec.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(spec.dimension().layers)
                        .baseMipLevel(index == 0 ? 0 : mip).levelCount(index == 0 ? spec.mipLevels() : 1);
                var descriptor = encoded.get(index).sType$Default().pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                writes.add(new GpuDescriptorWriter.ImageWrite(storageView ? GpuImageDescriptorKind.STORAGE : GpuImageDescriptorKind.SAMPLED, descriptor));
                if (storageView) storage.add(new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + index));
                else if (index > 0) mipSampled.add(new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + index));
            }
            gpu.descriptorHeap().writer().writeImages(descriptors, 0, writes);
            Texture texture = new Texture(spec.dimension(), spec.width(), spec.height(), spec.depth(), spec.mipLevels(), spec.format(),
                    image.image(), descriptors.firstIndex(), storage, mipSampled);
            return new OwnedImage(spec, image, descriptors, texture);
        } catch (RuntimeException | Error failure) {
            var allocatedImage = image; var allocatedDescriptors = descriptors;
            closeAfterFailure(failure, () -> { if (allocatedDescriptors != null) allocatedDescriptors.destroy(); },
                    () -> { if (allocatedImage != null) allocatedImage.close(); });
            throw failure;
        }
    }

    private static void checkImageSupport(GpuDevice gpu, ImageSpec spec) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var format = VkFormatProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFormatProperties2(gpu.vk().getPhysicalDevice(), spec.format(), format);
            int features = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                    | VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT | VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
            if (spec.linearFiltering()) features |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((format.formatProperties().optimalTilingFeatures() & features) != features) {
                throw new UnsupportedOperationException("Original Cloudly texture format unavailable: " + spec.name());
            }
            var query = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().format(spec.format())
                    .type(spec.dimension().imageType).tiling(VK_IMAGE_TILING_OPTIMAL).usage(USAGE)
                    .flags(spec.dimension() == Dimension.CUBE ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0);
            var properties = VkImageFormatProperties2.calloc(stack).sType$Default();
            int result = VK11.vkGetPhysicalDeviceImageFormatProperties2(gpu.vk().getPhysicalDevice(), query, properties);
            if (result != VK_SUCCESS) throw new UnsupportedOperationException("Original Cloudly texture image unsupported: " + spec.name() + " (" + result + ")");
            var limits = properties.imageFormatProperties();
            if (spec.width() > limits.maxExtent().width() || spec.height() > limits.maxExtent().height()
                    || spec.depth() > limits.maxExtent().depth() || spec.mipLevels() > limits.maxMipLevels()
                    || spec.dimension().layers > limits.maxArrayLayers()) {
                throw new UnsupportedOperationException("Original Cloudly texture dimensions exceed device limits: " + spec.name());
            }
        }
    }

    private static void writeSamplers(GpuDevice gpu, GpuDescriptorRange<GpuDescriptorIndex.Sampler> allocation,
                                      List<SamplerSpec> specs, Map<String, GpuDescriptorIndex.Sampler> indices) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int index = 0; index < specs.size(); index++) {
                SamplerSpec spec = specs.get(index);
                var sampler = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(spec.magFilter()).minFilter(spec.minFilter())
                        .mipmapMode(spec.mipmapMode()).addressModeU(spec.addressU()).addressModeV(spec.addressV())
                        .addressModeW(spec.addressW()).minLod(spec.minLod()).maxLod(spec.maxLod());
                gpu.descriptorHeap().writer().writeSampler(allocation, index, sampler);
                indices.put(spec.name(), new GpuDescriptorIndex.Sampler(allocation.firstIndex().value() + index));
            }
        }
    }

    private static void record(VkCommandBuffer commands, List<OwnedImage> images, List<RecordedStage> stages) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var transitions = VkImageMemoryBarrier2.calloc(images.size(), stack);
            for (int index = 0; index < images.size(); index++) {
                OwnedImage image = images.get(index);
                var transition = transitions.get(index).sType$Default().image(image.image().image())
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT | VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                transition.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(image.spec().mipLevels()).baseArrayLayer(0).layerCount(image.spec().dimension().layers);
            }
            VK14.vkCmdPipelineBarrier2(commands, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(transitions));
            for (OwnedImage image : images) {
                ClearValue clear = image.spec().clear();
                if (clear == null) continue;
                var value = VkClearColorValue.calloc(stack).uint32(0, clear.x()).uint32(1, clear.y()).uint32(2, clear.z()).uint32(3, clear.w());
                var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(image.spec().mipLevels()).baseArrayLayer(0).layerCount(image.spec().dimension().layers);
                vkCmdClearColorImage(commands, image.image().image(), VK_IMAGE_LAYOUT_GENERAL, value, range);
            }
            dependency(commands, stack);
            for (RecordedStage stage : stages) {
                try (MemoryStack dispatchStack = MemoryStack.stackPush()) {
                    if (stage instanceof RecordedCopy copy) {
                        var region = VkImageCopy.calloc(1, dispatchStack);
                        region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(copy.sourceMip()).baseArrayLayer(0).layerCount(copy.source().dimension().layers);
                        region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(copy.destinationMip()).baseArrayLayer(0).layerCount(copy.destination().dimension().layers);
                        region.extent().set(Math.max(1, copy.source().width() >> copy.sourceMip()),
                                Math.max(1, copy.source().height() >> copy.sourceMip()), Math.max(1, copy.source().depth() >> copy.sourceMip()));
                        vkCmdCopyImage(commands, copy.source().image(), VK_IMAGE_LAYOUT_GENERAL,
                                copy.destination().image(), VK_IMAGE_LAYOUT_GENERAL, region);
                        dependency(commands, dispatchStack);
                        continue;
                    }
                    RecordedDispatch dispatch = (RecordedDispatch) stage;
                    ByteBuffer push = dispatchStack.malloc(dispatch.shader().program().pushLayout().byteSize()).order(ByteOrder.LITTLE_ENDIAN);
                    dispatch.shader().program().pushLayout().write(push, dispatch.push());
                    dispatch.shader().dispatch(commands, push, dispatch.x(), dispatch.y(), dispatch.z());
                    dependency(commands, dispatchStack);
                }
            }
            var hostRead = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK13.VK_ACCESS_2_HOST_READ_BIT);
            VK14.vkCmdPipelineBarrier2(commands, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(hostRead));
        }
    }

    private static void dependency(VkCommandBuffer commands, MemoryStack stack) {
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT | VK13.VK_PIPELINE_STAGE_2_COPY_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                        | VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
        VK14.vkCmdPipelineBarrier2(commands, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Original Cloudly preparation cancelled");
    }
    private static void closeImages(List<OwnedImage> images, boolean published) {
        new ResourceLifetime(images.stream().filter(image -> image.spec().publish() == published)
                .<Runnable>map(image -> image::close).toArray(Runnable[]::new)).close();
    }
    private static void closeShaders(List<CloudlyShaderLibrary.Instance> shaders) {
        new ResourceLifetime(shaders.stream().<Runnable>map(shader -> shader::close).toArray(Runnable[]::new)).close();
    }
    private static void closeParameters(List<CloudlyShaderLibrary.Parameters> parameters) {
        new ResourceLifetime(parameters.stream().<Runnable>map(parameter -> parameter::close).toArray(Runnable[]::new)).close();
    }
    private record OwnedImage(ImageSpec spec, VmaImageAllocation image, GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                               Texture texture) implements AutoCloseable {
        @Override public void close() { new ResourceLifetime(descriptors::destroy, image::close).close(); }
    }
    static void validateCopy(CopySpec copy, Map<String, ImageSpec> images) {
        ImageSpec source = images.get(copy.source()), destination = images.get(copy.destination());
        if (source == null || destination == null || source.dimension() != destination.dimension()
                || source.format() != destination.format() || copy.sourceMip() < 0 || copy.sourceMip() >= source.mipLevels()
                || copy.destinationMip() < 0 || copy.destinationMip() >= destination.mipLevels()
                || copy.source().equals(copy.destination())) throw new IllegalArgumentException("Invalid original same-format image copy " + copy);
        int[] sourceSize = {source.width(), source.height(), source.depth()}, destinationSize = {destination.width(), destination.height(), destination.depth()};
        for (int axis = 0; axis < 3; axis++) if (Math.max(1, sourceSize[axis] >> copy.sourceMip()) != Math.max(1, destinationSize[axis] >> copy.destinationMip())) {
            throw new IllegalArgumentException("Original image copy extents differ " + copy);
        }
    }
    private sealed interface RecordedStage permits RecordedDispatch, RecordedCopy { }
    private record RecordedCopy(Texture source, int sourceMip, Texture destination, int destinationMip) implements RecordedStage { }
    private record RecordedDispatch(CloudlyShaderLibrary.Instance shader, Map<String, Long> push, int x, int y, int z) implements RecordedStage { }
}
