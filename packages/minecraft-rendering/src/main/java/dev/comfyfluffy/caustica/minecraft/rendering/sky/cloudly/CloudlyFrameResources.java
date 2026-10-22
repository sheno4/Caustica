package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkFormatProperties2;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkTexelBufferDescriptorInfoEXT;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.*;

/** Owns one explicit frame-resource epoch. It never creates substitutes for external source inputs. */
public final class CloudlyFrameResources implements AutoCloseable {
    private final CloudlyFrameSchedule schedule;
    private final Map<String, Image> images;
    private final Map<String, Buffer> buffers;
    private final Map<String, GpuDescriptorIndex.Sampler> samplers;
    private final ResourceOwner owner;
    private boolean initializationRecorded;
    private boolean closed;

    CloudlyFrameResources(CloudlyFrameSchedule schedule, Map<String, Image> images,
                                  Map<String, Buffer> buffers, Map<String, GpuDescriptorIndex.Sampler> samplers,
                                  ResourceOwner owner) {
        this.schedule = schedule;
        this.images = Map.copyOf(images);
        this.buffers = Map.copyOf(buffers);
        this.samplers = Map.copyOf(samplers);
        this.owner = owner;
    }

    /** Pure metadata decoding; does not query a Vulkan device, allocate, or record commands. */
    public static AllocationPlan inspect(CloudlyFrameSchedule schedule, CloudlyFrameSchedule.Resolved resolved) {
        List<ImageSpec> images = new ArrayList<>();
        List<BufferSpec> buffers = new ArrayList<>();
        List<SamplerSpec> samplers = new ArrayList<>();
        for (var resource : schedule.resources()) {
            JsonObject spec = resource.spec();
            switch (resource.kind()) {
                case IMAGE -> {
                    var extent = Objects.requireNonNull(resolved.images().get(resource.name()), "Missing resolved image " + resource.name());
                    String dimension = spec.get("dimension").getAsString();
                    if (!dimension.equals("TWO_D") && !dimension.equals("THREE_D")) {
                        throw new IllegalArgumentException("Frame allocation requires explicit 2D or 3D dimension");
                    }
                    if (extent.width() < 1 || extent.height() < 1 || extent.depth() < 1 || extent.mipLevels() < 1
                            || dimension.equals("TWO_D") && extent.depth() != 1
                            || extent.mipLevels() > 32 - Integer.numberOfLeadingZeros(Math.max(extent.width(), Math.max(extent.height(), extent.depth())))
                            || extent.format() != spec.get("format").getAsInt()
                            || extent.mipLevels() != spec.get("mipLevels").getAsInt()) {
                        throw new IllegalArgumentException("Invalid source frame extent " + resource.name());
                    }
                    scalarType(extent.format());
                    int usage = imageUsage(spec.getAsJsonArray("usage"));
                    if ((usage & (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT))
                            != (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) {
                        throw new IllegalArgumentException("Source frame images require both sampled and storage views");
                    }
                    boolean clear = spec.has("clearOnAllocation");
                    if (clear) requireZero(spec.getAsJsonArray("clearOnAllocation"));
                    if (clear && (usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                        throw new IllegalArgumentException("Source initialization clear needs TRANSFER_DST usage");
                    }
                    images.add(new ImageSpec(resource.name(), dimension, extent.width(), extent.height(), extent.depth(),
                            extent.mipLevels(), extent.format(), usage, clear, spec.get("lifetime").getAsString()));
                }
                case BUFFER -> {
                    var extent = Objects.requireNonNull(resolved.buffers().get(resource.name()), "Missing resolved buffer " + resource.name());
                    int usage = bufferUsage(spec.getAsJsonArray("usage"));
                    String type = extent.descriptorType();
                    String declaredType = spec.has("descriptorType") ? spec.get("descriptorType").getAsString() : "STORAGE_BUFFER";
                    int declaredFormat = spec.has("format") ? spec.get("format").getAsInt() : VK_FORMAT_UNDEFINED;
                    if (!type.equals(declaredType) || extent.format() != declaredFormat) {
                        throw new IllegalArgumentException("Resolved frame buffer differs from its source descriptor declaration");
                    }
                    int descriptorUsage = switch (type) {
                        case "STORAGE_BUFFER" -> VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                        case "STORAGE_TEXEL_BUFFER" -> VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;
                        default -> throw new IllegalArgumentException("Unsupported source frame buffer descriptor " + type);
                    };
                    if ((usage & descriptorUsage) == 0 || extent.strideBytes() < 1 || (extent.strideBytes() & 3) != 0
                            || extent.elements() < 1 || extent.byteSize() < 1
                            || extent.byteSize() != Math.multiplyExact(extent.strideBytes(), extent.elements())
                            || type.equals("STORAGE_TEXEL_BUFFER") && extent.format() == VK_FORMAT_UNDEFINED) {
                        throw new IllegalArgumentException("Source frame buffer usage or format does not match its descriptor");
                    }
                    if (type.equals("STORAGE_TEXEL_BUFFER") && extent.strideBytes() != texelBytes(extent.format())) {
                        throw new IllegalArgumentException("Source frame texel-buffer stride differs from its format");
                    }
                    buffers.add(new BufferSpec(resource.name(), extent.strideBytes(), extent.elements(), extent.byteSize(),
                            usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, type, extent.format()));
                }
                case SAMPLER -> {
                    JsonArray address = spec.getAsJsonArray("address");
                    if (address.size() != 3 || spec.has("comparison") && spec.get("comparison").getAsInt() != 0
                            || spec.has("maxAnisotropy") && spec.get("maxAnisotropy").getAsFloat() != 1) {
                        throw new IllegalArgumentException("Frame source sampler needs an explicitly supported comparison/anisotropy mode");
                    }
                    var sampler = new SamplerSpec(resource.name(), spec.get("filter").getAsInt(), spec.get("mipmapMode").getAsInt(),
                            address.get(0).getAsInt(), address.get(1).getAsInt(), address.get(2).getAsInt(),
                            spec.get("minLod").getAsFloat(), spec.get("maxLod").getAsFloat());
                    if ((sampler.filter() != VK_FILTER_NEAREST && sampler.filter() != VK_FILTER_LINEAR)
                            || (sampler.mipmapMode() != VK_SAMPLER_MIPMAP_MODE_NEAREST && sampler.mipmapMode() != VK_SAMPLER_MIPMAP_MODE_LINEAR)
                            || !validAddressMode(sampler.addressU()) || !validAddressMode(sampler.addressV()) || !validAddressMode(sampler.addressW())
                            || !Float.isFinite(sampler.minLod()) || !Float.isFinite(sampler.maxLod())
                            || sampler.minLod() < 0 || sampler.maxLod() < sampler.minLod()) {
                        throw new IllegalArgumentException("Unsupported source frame sampler state");
                    }
                    samplers.add(sampler);
                }
                case EXTERNAL -> { }
            }
        }
        if (images.size() != resolved.images().size() || buffers.size() != resolved.buffers().size()) {
            throw new IllegalArgumentException("Resolved resources belong to a different frame schedule");
        }
        var plan = new AllocationPlan(images, buffers, samplers);
        for (var stage : schedule.stages()) {
            JsonObject spec = stage.spec();
            if (spec.has("barrierBefore")) decodeBarrier(spec.getAsJsonObject("barrierBefore"));
            if (spec.has("barrierAfter")) decodeBarrier(spec.getAsJsonObject("barrierAfter"));
            if (stage.operation().equals("CLEAR")) inspectClear(plan, stage);
        }
        for (var dispatch : resolved.dispatches().values()) {
            if (dispatch instanceof CloudlyFrameSchedule.IndirectDispatch indirect) {
                BufferSpec buffer = plan.buffers().stream().filter(value -> value.name().equals(indirect.argumentBuffer())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Missing owned indirect argument buffer " + indirect.argumentBuffer()));
                validateIndirectOffset(buffer.usage(), buffer.byteSize(), indirect.byteOffset());
            }
        }
        return plan;
    }

    /**
     * Allocates original sizes and formats. The caller decides when an epoch replaces a prior size;
     * each prior frame keeps its own retained owner until all uses retire. Histories are never silently resized.
     */
    public static CloudlyFrameResources allocate(GpuDevice gpu, ResourceFactory resources,
                                                 CloudlyFrameSchedule schedule, CloudlyFrameSchedule.Resolved resolved) {
        AllocationPlan plan = inspect(schedule, resolved);
        List<OwnedImage> ownedImages = new ArrayList<>();
        List<OwnedBuffer> ownedBuffers = new ArrayList<>();
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerRange = null;
        ResourceOwner owner = null;
        try {
            Map<String, Image> images = new LinkedHashMap<>();
            Map<String, Buffer> buffers = new LinkedHashMap<>();
            Map<String, GpuDescriptorIndex.Sampler> samplers = new LinkedHashMap<>();
            for (ImageSpec spec : plan.images()) {
                var allocation = allocateImage(gpu, spec);
                ownedImages.add(allocation);
                images.put(spec.name(), allocation.image());
            }
            for (BufferSpec spec : plan.buffers()) {
                var allocation = allocateBuffer(gpu, spec);
                ownedBuffers.add(allocation);
                buffers.put(spec.name(), allocation.buffer());
            }
            if (!plan.samplers().isEmpty()) {
                samplerRange = gpu.descriptorHeap().allocateSamplers(plan.samplers().size());
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    for (int index = 0; index < plan.samplers().size(); index++) {
                        var spec = plan.samplers().get(index);
                        var info = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(spec.filter()).minFilter(spec.filter())
                                .mipmapMode(spec.mipmapMode()).addressModeU(spec.addressU()).addressModeV(spec.addressV())
                                .addressModeW(spec.addressW()).minLod(spec.minLod()).maxLod(spec.maxLod())
                                .anisotropyEnable(false).maxAnisotropy(1).compareEnable(false);
                        gpu.descriptorHeap().writer().writeSampler(samplerRange, index, info);
                        samplers.put(spec.name(), new GpuDescriptorIndex.Sampler(samplerRange.firstIndex().value() + index));
                    }
                }
            }
            var descriptors = samplerRange;
            owner = resources.create(() -> new ResourceLifetime(
                    () -> { if (descriptors != null) descriptors.destroy(); },
                    () -> closeBuffers(ownedBuffers), () -> closeImages(ownedImages)).close());
            return new CloudlyFrameResources(schedule, images, buffers, samplers, owner);
        } catch (RuntimeException | Error failure) {
            ResourceOwner allocatedOwner = owner;
            var descriptors = samplerRange;
            ResourceLifetime.closeAfterFailure(failure, () -> {
                if (allocatedOwner != null) allocatedOwner.close();
                else new ResourceLifetime(() -> { if (descriptors != null) descriptors.destroy(); },
                        () -> closeBuffers(ownedBuffers), () -> closeImages(ownedImages)).close();
            });
            throw failure;
        }
    }

    public Map<String, Image> images() { requireOpen(); return images; }
    public Map<String, Buffer> buffers() { requireOpen(); return buffers; }
    public Image image(String name) { requireOpen(); return Objects.requireNonNull(images.get(name), "Unknown owned frame image " + name); }
    public Buffer buffer(String name) { requireOpen(); return Objects.requireNonNull(buffers.get(name), "Unknown owned frame buffer " + name); }
    public Buffer indirectBuffer(String name, long byteOffset) {
        Buffer buffer = buffer(name);
        buffer.validateIndirectOffset(byteOffset);
        return buffer;
    }
    public GpuDescriptorIndex.Sampler sampler(String name) { requireOpen(); return Objects.requireNonNull(samplers.get(name), "Unknown owned frame sampler " + name); }
    /** Borrowed claim; callers retain it through the frame rather than closing this handle. */
    public ResourceOwner owner() { requireOpen(); return owner; }
    public ResourceOwner retain() { requireOpen(); return owner.retain(); }

    /** External descriptors and any native ring-role aliases must be resolved explicitly by the caller. */
    public GpuDescriptorIndex descriptor(String name, CloudlyShaderLibrary.BindingField field) {
        requireOpen();
        if (field.heap().equals("Sampler")) {
            if (!field.type().equals("SamplerState")) throw new IllegalArgumentException("Source sampler comparison mode is unsupported");
            return sampler(name);
        }
        if (!field.heap().equals("Resource")) throw new IllegalArgumentException("Unknown source descriptor heap");
        if (samplers.containsKey(name)) throw new IllegalArgumentException("Source resource field cannot use a sampler descriptor");
        Image image = images.get(name);
        if (image != null) {
            String dimension = image.dimension().equals("TWO_D") ? "2D" : "3D";
            if (!(field.type().startsWith("Texture" + dimension + "<") || field.type().startsWith("RWTexture" + dimension + "<"))) {
                throw new IllegalArgumentException("Source frame image view does not match " + field.type());
            }
            requireFormatScalar(image.format(), field.type());
            return field.type().startsWith("RW") ? image.storage(0) : image.sampled();
        }
        Buffer buffer = buffers.get(name);
        if (buffer != null) {
            boolean structured = field.type().startsWith("StructuredBuffer<") || field.type().startsWith("RWStructuredBuffer<");
            if (structured != buffer.descriptorType().equals("STORAGE_BUFFER")
                    || !structured && !field.type().startsWith("RWBuffer<")) {
                throw new IllegalArgumentException("Source frame buffer descriptor does not match " + field.type());
            }
            if (structured) {
                if (elementBytes(field.type()) != buffer.strideBytes()) {
                    throw new IllegalArgumentException("Original structured-buffer element and stride differ");
                }
            } else requireFormatScalar(buffer.format(), field.type());
            return buffer.descriptor();
        }
        throw new IllegalArgumentException("External source resource must be supplied by its actual producer: " + name);
    }

    /**
     * Records exactly once for a newly allocated epoch, before its first use in the same ordered frame.
     * Only explicitly declared first-allocation clears are emitted. If that submission is abandoned,
     * discard this epoch; recording is not proof that its GPU initialization completed.
     */
    public synchronized void recordInitialization(PassFrame frame) {
        requireOpen();
        if (initializationRecorded) throw new IllegalStateException("Frame-resource initialization was already recorded");
        frame.retain(owner);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!images.isEmpty()) {
                var transitions = VkImageMemoryBarrier2.calloc(images.size(), stack);
                int index = 0;
                for (Image image : images.values()) {
                    var transition = transitions.get(index++).sType$Default().image(image.handle())
                            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                            .dstStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                            .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT | VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                    transition.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(1)
                            .baseMipLevel(0).levelCount(image.mipLevels());
                }
                VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(transitions));
            }
            boolean cleared = false;
            for (var resource : schedule.resources()) {
                if (resource.kind() != CloudlyFrameSchedule.Kind.IMAGE || !resource.spec().has("clearOnAllocation")) continue;
                clearImage(frame, stack, image(resource.name()));
                cleared = true;
            }
            if (cleared) transferToCompute(frame, stack);
            initializationRecorded = true;
        }
    }

    /** Original per-frame clear stages are explicit; histories are not reset by this operation. */
    public void recordClear(PassFrame frame, String stageName) {
        requireOpen();
        requireInitialized();
        var stage = schedule.stage(stageName);
        if (!stage.operation().equals("CLEAR")) throw new IllegalArgumentException("Frame stage does not declare a clear");
        requireZero(stage.spec().getAsJsonArray("value"));
        frame.retain(owner);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            computeToClear(frame, stack);
            if (stage.spec().has("barrierBefore")) declaredBarrier(frame, stack, stage.spec().getAsJsonObject("barrierBefore"));
            for (JsonElement element : stage.spec().getAsJsonArray("resources")) {
                String name = element.getAsString();
                if (images.containsKey(name)) {
                    if ((image(name).usage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) throw new IllegalArgumentException("Clear image lacks TRANSFER_DST");
                    clearImage(frame, stack, image(name));
                } else {
                    Buffer buffer = buffer(name);
                    if ((buffer.usage() & VK_BUFFER_USAGE_TRANSFER_DST_BIT) == 0 || (buffer.byteSize() & 3) != 0) {
                        throw new IllegalArgumentException("Source fill requires TRANSFER_DST and a multiple of four bytes");
                    }
                    vkCmdFillBuffer(frame.commandBuffer(), buffer.handle(), 0, buffer.byteSize(), 0);
                }
            }
            if (stage.spec().has("barrierAfter")) declaredBarrier(frame, stack, stage.spec().getAsJsonObject("barrierAfter"));
        }
    }

    /** Records the memory dependency stated by the plan; resource state and submission remain caller-owned. */
    public void recordBarrier(PassFrame frame, String stageName) {
        requireOpen();
        requireInitialized();
        var stage = schedule.stage(stageName);
        if (!stage.spec().has("barrierAfter")) return;
        frame.retain(owner);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            declaredBarrier(frame, stack, stage.spec().getAsJsonObject("barrierAfter"));
        }
    }

    private static OwnedImage allocateImage(GpuDevice gpu, ImageSpec spec) {
        VmaImageAllocation allocation = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var features = VkFormatProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFormatProperties2(gpu.vk().getPhysicalDevice(), spec.format(), features);
            int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((spec.usage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0) required |= VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if ((spec.usage() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0) required |= VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
            if ((features.formatProperties().optimalTilingFeatures() & required) != required) {
                throw new UnsupportedOperationException("Original frame texture format is unsupported: " + spec.name());
            }
            boolean twoD = spec.dimension().equals("TWO_D");
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(twoD ? VK_IMAGE_TYPE_2D : VK_IMAGE_TYPE_3D)
                    .format(spec.format()).mipLevels(spec.mipLevels()).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(spec.usage()).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(spec.width(), spec.height(), spec.depth());
            allocation = VmaImageAllocation.create(gpu, info, "Cloudly original frame " + spec.name());
            descriptors = gpu.descriptorHeap().allocateResources(1 + spec.mipLevels());
            var views = VkImageViewCreateInfo.calloc(1 + spec.mipLevels(), stack);
            var encoded = VkImageDescriptorInfoEXT.calloc(1 + spec.mipLevels(), stack);
            List<GpuDescriptorWriter.ImageWrite> writes = new ArrayList<>();
            List<GpuDescriptorIndex.Resource> storage = new ArrayList<>();
            for (int index = 0; index <= spec.mipLevels(); index++) {
                var view = views.get(index).sType$Default().image(allocation.image())
                        .viewType(twoD ? VK_IMAGE_VIEW_TYPE_2D : VK_IMAGE_VIEW_TYPE_3D).format(spec.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(1)
                        .baseMipLevel(index == 0 ? 0 : index - 1).levelCount(index == 0 ? spec.mipLevels() : 1);
                var descriptor = encoded.get(index).sType$Default().pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                writes.add(new GpuDescriptorWriter.ImageWrite(index == 0 ? GpuImageDescriptorKind.SAMPLED : GpuImageDescriptorKind.STORAGE, descriptor));
                if (index != 0) storage.add(new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + index));
            }
            gpu.descriptorHeap().writer().writeImages(descriptors, 0, writes);
            var image = new Image(spec.name(), spec.dimension(), spec.width(), spec.height(), spec.depth(), spec.mipLevels(),
                    spec.format(), spec.usage(), allocation.image(), descriptors.firstIndex(), storage);
            return new OwnedImage(allocation, descriptors, image);
        } catch (RuntimeException | Error failure) {
            var image = allocation;
            var range = descriptors;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (range != null) range.destroy(); }, () -> { if (image != null) image.close(); });
            throw failure;
        }
    }

    private static OwnedBuffer allocateBuffer(GpuDevice gpu, BufferSpec spec) {
        long handle = 0, allocation = 0;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (spec.descriptorType().equals("STORAGE_TEXEL_BUFFER")) {
                var format = VkFormatProperties2.calloc(stack).sType$Default();
                VK11.vkGetPhysicalDeviceFormatProperties2(gpu.vk().getPhysicalDevice(), spec.format(), format);
                if ((format.formatProperties().bufferFeatures() & VK_FORMAT_FEATURE_STORAGE_TEXEL_BUFFER_BIT) == 0) {
                    throw new UnsupportedOperationException("Original frame texel-buffer format is unsupported");
                }
            }
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(spec.byteSize()).usage(spec.usage()).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            int[] families = gpu.asyncBufferSharingQueueFamilies();
            if (families.length > 1) info.sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(families));
            var vmaInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var bufferOut = stack.callocLong(1);
            var allocationOut = stack.callocPointer(1);
            int result = Vma.vmaCreateBuffer(gpu.vmaAllocator(), info, vmaInfo, bufferOut, allocationOut, null);
            handle = bufferOut.get(0); allocation = allocationOut.get(0);
            if (result != VK_SUCCESS) throw new IllegalStateException("vmaCreateBuffer(Cloudly original frame " + spec.name() + "): " + result);
            Vma.vmaSetAllocationName(gpu.vmaAllocator(), allocation, "Cloudly original frame " + spec.name());
            long address = VK12.vkGetBufferDeviceAddress(gpu.vk(), VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
            if (address == 0) throw new IllegalStateException("Source frame buffer needs a nonzero device address");
            descriptors = gpu.descriptorHeap().allocateResources(1);
            var encoded = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default();
            if (spec.descriptorType().equals("STORAGE_TEXEL_BUFFER")) {
                var texels = VkTexelBufferDescriptorInfoEXT.calloc(stack).sType$Default().format(spec.format())
                        .addressRange(range -> range.address$(address).size(spec.byteSize()));
                encoded.type(VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER).data(data -> data.pTexelBuffer(texels));
            } else {
                encoded.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).data(data -> data.pAddressRange(
                        org.lwjgl.vulkan.VkDeviceAddressRangeEXT.calloc(stack).address$(address).size(spec.byteSize())));
            }
            gpu.descriptorHeap().writer().writeResource(descriptors, 0, encoded);
            var buffer = new Buffer(spec.name(), spec.strideBytes(), spec.elements(), spec.byteSize(), spec.usage(),
                    spec.descriptorType(), spec.format(), handle, new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), spec.byteSize()),
                    descriptors.firstIndex());
            return new OwnedBuffer(gpu.vmaAllocator(), handle, allocation, descriptors, buffer);
        } catch (RuntimeException | Error failure) {
            long buffer = handle, memory = allocation;
            var range = descriptors;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (range != null) range.destroy(); },
                    () -> { if (buffer != 0) Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, memory); });
            throw failure;
        }
    }

    private static void clearImage(PassFrame frame, MemoryStack stack, Image image) {
        var value = VkClearColorValue.calloc(stack);
        var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseArrayLayer(0).layerCount(1).baseMipLevel(0).levelCount(image.mipLevels());
        vkCmdClearColorImage(frame.commandBuffer(), image.handle(), VK_IMAGE_LAYOUT_GENERAL, value, range);
    }

    private static void transferToCompute(PassFrame frame, MemoryStack stack) {
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT).srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
        VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static void declaredBarrier(PassFrame frame, MemoryStack stack, JsonObject spec) {
        var decoded = decodeBarrier(spec);
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(decoded.sourceStage()).srcAccessMask(decoded.sourceAccess())
                .dstStageMask(decoded.destinationStage()).dstAccessMask(decoded.destinationAccess());
        VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static void computeToClear(PassFrame frame, MemoryStack stack) {
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
                        | VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT
                        | VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK13.VK_ACCESS_2_TRANSFER_READ_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT).dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
        VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static Barrier decodeBarrier(JsonObject spec) {
        return new Barrier(stageMask(spec.get("sourceStage")), accessMask(spec.getAsJsonArray("sourceAccess")),
                stageMask(spec.get("destinationStage")), accessMask(spec.getAsJsonArray("destinationAccess")));
    }

    private record Barrier(long sourceStage, long sourceAccess, long destinationStage, long destinationAccess) { }

    /** Checks all clear targets before allocation or command recording can publish a partial operation. */
    static void inspectClear(AllocationPlan plan, CloudlyFrameSchedule.Stage stage) {
        JsonObject spec = stage.spec();
        requireZero(spec.getAsJsonArray("value"));
        var names = new HashSet<String>();
        for (JsonElement target : spec.getAsJsonArray("resources")) {
            String name = target.getAsString();
            if (!names.add(name)) throw new IllegalArgumentException("Repeated original frame clear target " + name);
            ImageSpec image = plan.images().stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
            if (image != null) {
                if ((image.usage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) throw new IllegalArgumentException("Clear image lacks TRANSFER_DST");
            } else {
                BufferSpec buffer = plan.buffers().stream().filter(value -> value.name().equals(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Clear target must be an owned image or buffer: " + name));
                if ((buffer.usage() & VK_BUFFER_USAGE_TRANSFER_DST_BIT) == 0 || (buffer.byteSize() & 3) != 0) {
                    throw new IllegalArgumentException("Source fill requires TRANSFER_DST and a multiple of four bytes");
                }
            }
        }
    }

    private static long stageMask(JsonElement spec) {
        if (spec.isJsonArray()) {
            long result = 0;
            for (JsonElement name : spec.getAsJsonArray()) result |= stageMask(name);
            return result;
        }
        return switch (spec.getAsString()) {
            case "TRANSFER" -> VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;
            case "COMPUTE_SHADER" -> VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            case "DRAW_INDIRECT" -> VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
            case "TRANSFER_OR_HOST_COMPUTE" -> VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            default -> throw new IllegalArgumentException("Unsupported declared frame barrier stage " + spec.getAsString());
        };
    }

    private static long accessMask(JsonArray names) {
        long result = 0;
        for (JsonElement name : names) result |= switch (name.getAsString()) {
            case "SHADER_READ" -> VK13.VK_ACCESS_2_SHADER_READ_BIT;
            case "SHADER_WRITE" -> VK13.VK_ACCESS_2_SHADER_WRITE_BIT;
            case "TRANSFER_WRITE" -> VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
            case "INDIRECT_COMMAND_READ" -> VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
            default -> throw new IllegalArgumentException("Unsupported declared frame barrier access " + name.getAsString());
        };
        return result;
    }

    private static int imageUsage(JsonArray names) {
        int result = 0;
        for (JsonElement name : names) result |= switch (name.getAsString()) {
            case "SAMPLED" -> VK_IMAGE_USAGE_SAMPLED_BIT;
            case "STORAGE" -> VK_IMAGE_USAGE_STORAGE_BIT;
            case "TRANSFER_DST" -> VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            case "TRANSFER_SRC" -> VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            default -> throw new IllegalArgumentException("Unsupported source frame image usage " + name.getAsString());
        };
        return result;
    }

    private static int bufferUsage(JsonArray names) {
        int result = 0;
        for (JsonElement name : names) result |= switch (name.getAsString()) {
            case "STORAGE_BUFFER" -> VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            case "STORAGE_TEXEL_BUFFER" -> VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;
            case "INDIRECT_BUFFER" -> VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
            case "TRANSFER_DST" -> VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            case "TRANSFER_SRC" -> VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            default -> throw new IllegalArgumentException("Unsupported source frame buffer usage " + name.getAsString());
        };
        return result;
    }

    private static void requireZero(JsonArray values) {
        if (values == null || values.size() != 4 || values.asList().stream().anyMatch(value -> value.getAsDouble() != 0)) {
            throw new IllegalArgumentException("Frame plan must explicitly declare a four-component zero clear");
        }
    }

    static void validateIndirectOffset(int usage, long bytes, long offset) {
        if ((usage & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) == 0 || offset < 0 || (offset & 3) != 0
                || bytes < 12 || offset > bytes - 12) {
            throw new IllegalArgumentException("Indirect dispatch requires a contained, four-byte-aligned uint3 command");
        }
    }

    private static boolean validAddressMode(int mode) {
        return mode >= VK_SAMPLER_ADDRESS_MODE_REPEAT && mode <= VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
    }

    static void requireFormatScalar(int format, String type) {
        int start = type.indexOf('<'), end = type.indexOf('>');
        if (start < 0 || end < start) throw new IllegalArgumentException("Missing source descriptor element type");
        int comma = type.indexOf(',', start + 1);
        String element = type.substring(start + 1, comma >= 0 && comma < end ? comma : end).trim();
        String expected = null;
        for (String scalar : List.of("float", "half", "uint", "int")) {
            if (element.equals(scalar) || element.startsWith(scalar) && element.length() == scalar.length() + 1
                    && element.charAt(scalar.length()) >= '1' && element.charAt(scalar.length()) <= '4') {
                expected = scalar.equals("half") ? "float" : scalar;
                break;
            }
        }
        if (!scalarType(format).equals(expected)) throw new IllegalArgumentException("Source descriptor scalar type differs from image format");
    }

    private static String scalarType(int format) {
        return switch (format) {
            case VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT,
                    VK_FORMAT_B10G11R11_UFLOAT_PACK32 -> "float";
            case VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R32_UINT,
                    VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32A32_UINT -> "uint";
            case VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32A32_SINT -> "int";
            default -> throw new IllegalArgumentException("Unsupported original frame scalar format " + format);
        };
    }

    private static int texelBytes(int format) {
        return switch (format) {
            case VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_R32_SFLOAT -> 4;
            case VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32_SFLOAT -> 8;
            case VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT, VK_FORMAT_R32G32B32A32_SFLOAT -> 16;
            default -> throw new IllegalArgumentException("Unsupported source frame texel buffer format " + format);
        };
    }

    private static int elementBytes(String type) {
        int start = type.indexOf('<'), end = type.indexOf('>');
        if (start < 0 || end < start) throw new IllegalArgumentException("Missing original structured element type");
        String element = type.substring(start + 1, end);
        for (String scalar : List.of("float", "uint", "int")) {
            if (element.equals(scalar)) return 4;
            if (element.startsWith(scalar) && element.length() == scalar.length() + 1) {
                int count = element.charAt(element.length() - 1) - '0';
                if (count >= 2 && count <= 4) return 4 * count;
            }
        }
        throw new IllegalArgumentException("Unsupported original structured element " + element);
    }

    private void requireInitialized() {
        if (!initializationRecorded) throw new IllegalStateException("Original frame resource epoch has not been initialized");
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Frame-resource epoch was closed"); }
    private static void closeImages(List<OwnedImage> allocations) {
        new ResourceLifetime(allocations.stream().<Runnable>map(image -> image::close).toArray(Runnable[]::new)).close();
    }
    private static void closeBuffers(List<OwnedBuffer> allocations) {
        new ResourceLifetime(allocations.stream().<Runnable>map(buffer -> buffer::close).toArray(Runnable[]::new)).close();
    }
    @Override public void close() { if (!closed) { closed = true; owner.close(); } }

    public record AllocationPlan(List<ImageSpec> images, List<BufferSpec> buffers, List<SamplerSpec> samplers) {
        public AllocationPlan { images = List.copyOf(images); buffers = List.copyOf(buffers); samplers = List.copyOf(samplers); }
    }
    public record ImageSpec(String name, String dimension, int width, int height, int depth, int mipLevels,
                            int format, int usage, boolean clearOnAllocation, String lifetime) { }
    public record BufferSpec(String name, int strideBytes, long elements, long byteSize, int usage, String descriptorType, int format) { }
    public record SamplerSpec(String name, int filter, int mipmapMode, int addressU, int addressV, int addressW, float minLod, float maxLod) { }
    public record Image(String name, String dimension, int width, int height, int depth, int mipLevels, int format,
                        int usage, long handle, GpuDescriptorIndex.Resource sampled, List<GpuDescriptorIndex.Resource> storage) {
        public Image { storage = List.copyOf(storage); }
        public GpuDescriptorIndex.Resource storage(int mip) { return storage.get(mip); }
    }
    public record Buffer(String name, int strideBytes, long elements, long byteSize, int usage, String descriptorType, int format,
                         long handle, VulkanDeviceAddressRange deviceRange, GpuDescriptorIndex.Resource descriptor) {
        public void validateIndirectOffset(long offset) { CloudlyFrameResources.validateIndirectOffset(usage, byteSize, offset); }
    }
    private record OwnedImage(VmaImageAllocation allocation, GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors, Image image) {
        void close() { new ResourceLifetime(descriptors::destroy, allocation::close).close(); }
    }
    private record OwnedBuffer(long allocator, long handle, long allocation,
                               GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors, Buffer buffer) {
        void close() { new ResourceLifetime(descriptors::destroy, () -> Vma.vmaDestroyBuffer(allocator, handle, allocation)).close(); }
    }
}
