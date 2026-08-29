package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftImplementationData;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftMaterialData;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/** Session-wide sampler and factory for immutable Minecraft program/material epochs. */
public final class MinecraftProgramResources implements AutoCloseable {
    private final GpuDevice gpu;
    private final GpuDescriptorRange<GpuDescriptorIndex.Sampler> sampler;
    private int liveEpochs;
    private boolean closed;

    public MinecraftProgramResources(GpuDevice gpu) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        sampler = gpu.descriptorHeap().allocateSamplers(1, "Minecraft material sampler");
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gpu.descriptorHeap().writer().writeSampler(sampler, 0,
                        VkSamplerCreateInfo.calloc(stack).sType$Default()
                                .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                                .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                                .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                                .minLod(0f).maxLod(Float.MAX_VALUE));
            }
        } catch (RuntimeException | Error failure) {
            sampler.destroy();
            throw failure;
        }
    }

    /**
     * Takes ownership of an uploaded image lease for every CPU texture and creates one immutable program
     * epoch. The caller publishes {@link Epoch#implementationData()} with the matching geometry IDs and
     * gives {@link Epoch#retirement()} to that atomic program registration's retirement path.
     */
    public synchronized Epoch createEpoch(MinecraftMaterialLookup lookup,
                                          List<? extends UploadedImage> images) {
        requireOpen();
        java.util.Objects.requireNonNull(lookup, "lookup");
        List<UploadedImage> ownedImages = List.copyOf(images);
        if (ownedImages.size() != lookup.textures().size()) {
            closeImages(ownedImages);
            throw new IllegalArgumentException("uploaded image count must match the epoch CPU texture count");
        }
        try {
            validateTextureOrdinals(lookup.records(), ownedImages.size());
            Epoch epoch = createEpoch(lookup.records(), ownedImages);
            liveEpochs++;
            return epoch;
        } catch (RuntimeException | Error failure) {
            try {
                closeImages(ownedImages);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Creates the nonzero fallback root used before the first resource-pack upload. */
    public synchronized Epoch createFallbackEpoch() {
        requireOpen();
        Epoch epoch = createEpoch(List.of(MinecraftMaterialRecord.fallback()), List.of());
        liveEpochs++;
        return epoch;
    }

    private Epoch createEpoch(List<MinecraftMaterialRecord> records, List<UploadedImage> images) {
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        Buffer materialTable = null;
        try {
            if (!images.isEmpty()) {
                descriptors = gpu.descriptorHeap().allocateResources(images.size(), "Minecraft material textures");
                writeDescriptors(descriptors, images);
            }
            int firstDescriptor = descriptors == null ? 0 : descriptors.firstIndex().value();
            long tableSize = Math.multiplyExact((long) records.size(), MinecraftMaterialData.BYTE_SIZE);
            materialTable = create(tableSize, bytes -> {
                for (int index = 0; index < records.size(); index++) {
                    ByteBuffer destination = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    destination.position(Math.multiplyExact(index, MinecraftMaterialData.BYTE_SIZE));
                    destination.limit(destination.position() + MinecraftMaterialData.BYTE_SIZE);
                    records.get(index).shaderData(ordinal -> Math.addExact(firstDescriptor, ordinal))
                            .write(destination.slice().order(ByteOrder.LITTLE_ENDIAN));
                }
            });
            Buffer table = materialTable;
            Buffer implementation = create(MinecraftImplementationData.BYTE_SIZE,
                    bytes -> new MinecraftImplementationData(table.address,
                            new MinecraftImplementationData.SamplerIndex(sampler.firstIndex().value()), 0).write(bytes));
            return new Epoch(descriptors, materialTable, implementation, images);
        } catch (RuntimeException | Error failure) {
            if (materialTable != null) materialTable.destroy();
            if (descriptors != null) descriptors.destroy();
            throw failure;
        }
    }

    private void writeDescriptors(GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                                  List<UploadedImage> images) {
        for (int index = 0; index < images.size(); index++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                UploadedImage image = images.get(index);
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(image.image()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(image.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(image.mipLevels()).baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT info = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                VkResourceDescriptorInfoEXT resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                        .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(info));
                gpu.descriptorHeap().writer().writeResource(descriptors, index, resource);
            }
        }
    }

    private static void validateTextureOrdinals(List<MinecraftMaterialRecord> records, int textureCount) {
        for (MinecraftMaterialRecord record : records) {
            int features = record.features();
            if ((features & MinecraftMaterialPageCompiler.FEATURE_SPEC) != 0) {
                requireTexture(record.surface0Texture(), textureCount);
                requireTexture(record.surface1Texture(), textureCount);
            }
            if ((features & MinecraftMaterialPageCompiler.FEATURE_NORMAL) != 0) {
                requireTexture(record.normalTexture(), textureCount);
            }
            if ((features & MinecraftMaterialPageCompiler.FEATURE_EMISSION_MASK) != 0) {
                requireTexture(record.surface0Texture(), textureCount);
                requireTexture(record.emissionTexture(), textureCount);
            }
        }
    }

    private static void requireTexture(int ordinal, int textureCount) {
        if (ordinal >= textureCount) {
            throw new IllegalArgumentException("material texture ordinal exceeds the uploaded image batch");
        }
    }

    private Buffer create(long size, Writer writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer outBuffer = stack.mallocLong(1);
            PointerBuffer outAllocation = stack.mallocPointer(1);
            VmaAllocationInfo outInfo = VmaAllocationInfo.calloc(stack);
            int result = vmaCreateBuffer(gpu.vmaAllocator(), bufferInfo, allocationInfo,
                    outBuffer, outAllocation, outInfo);
            if (result != VK_SUCCESS) throw new IllegalStateException("vmaCreateBuffer failed: " + result);
            long handle = outBuffer.get(0);
            long allocation = outAllocation.get(0);
            long address = vkGetBufferDeviceAddress(gpu.vk(),
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
            if (address == 0L || outInfo.pMappedData() == 0L) {
                Vma.vmaDestroyBuffer(gpu.vmaAllocator(), handle, allocation);
                throw new IllegalStateException("Minecraft program buffer is not mapped and device-addressable");
            }
            ByteBuffer bytes = MemoryUtil.memByteBuffer(outInfo.pMappedData(), Math.toIntExact(size))
                    .order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            Vma.vmaFlushAllocation(gpu.vmaAllocator(), allocation, 0, size);
            return new Buffer(handle, allocation, address);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Minecraft program resources are closed");
    }

    @Override public synchronized void close() {
        if (closed) return;
        if (liveEpochs != 0) {
            throw new IllegalStateException("Minecraft program epochs must retire before the session sampler");
        }
        closed = true;
        sampler.destroy();
    }

    /** Uploader-owned image lease transferred into exactly one material epoch. */
    public interface UploadedImage extends AutoCloseable {
        long image();
        int format();
        int mipLevels();
        @Override void close();
    }

    /** Immutable root and all resource-pack GPU resources captured by one program registration. */
    public final class Epoch implements AutoCloseable {
        private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
        private final Buffer materialTable;
        private final Buffer implementation;
        private final List<UploadedImage> images;
        private final Runnable retirement = this::retire;
        private boolean closed;
        private Epoch(GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                      Buffer materialTable, Buffer implementation, List<UploadedImage> images) {
            this.descriptors = descriptors;
            this.materialTable = materialTable;
            this.implementation = implementation;
            this.images = images;
        }
        public ShaderData<MinecraftProvidersExtension.ImplementationData> implementationData() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            return MinecraftProvidersExtension.IMPLEMENTATION_DATA.data(implementation.address);
        }
        public Runnable retirement() { return retirement; }
        @Override public void close() {
            Throwable failure = release();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
        private void retire() {
            Throwable failure = release();
            if (failure != null) {
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.error(
                        "Minecraft material epoch retirement cleanup failed", failure);
            }
        }
        private synchronized Throwable release() {
            if (closed) return null;
            closed = true;
            Throwable failure = release(null, implementation::destroy);
            failure = release(failure, materialTable::destroy);
            if (descriptors != null) failure = release(failure, descriptors::destroy);
            try {
                for (UploadedImage image : images) failure = release(failure, image::close);
            } finally {
                synchronized (MinecraftProgramResources.this) {
                    liveEpochs--;
                }
            }
            return failure;
        }
    }

    private static Throwable release(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            if (failure == null) return cleanupFailure;
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private static void closeImages(List<? extends UploadedImage> images) {
        RuntimeException failure = null;
        for (UploadedImage image : images) {
            try {
                image.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private final class Buffer {
        private final long handle;
        private final long allocation;
        private final long address;
        private boolean destroyed;
        private Buffer(long handle, long allocation, long address) {
            this.handle = handle;
            this.allocation = allocation;
            this.address = address;
        }
        private void destroy() {
            if (destroyed) return;
            Vma.vmaDestroyBuffer(gpu.vmaAllocator(), handle, allocation);
            destroyed = true;
        }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer destination); }
}
