package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftImplementationData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftMaterialData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/** Session-wide sampler and factory for immutable Minecraft program/material epochs. */
public final class MinecraftProgramResources implements AutoCloseable {
    private final GpuDevice gpu;
    private final GpuComputeQueue compute;
    private final ResourceFactory resourceFactory;
    private final GpuDescriptorRange<GpuDescriptorIndex.Sampler> sampler;
    private final ResourceOwner samplerOwner;
    private boolean closed;

    public MinecraftProgramResources(GpuDevice gpu, GpuComputeQueue compute,
                                     ResourceFactory resourceFactory) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.compute = java.util.Objects.requireNonNull(compute, "compute");
        this.resourceFactory = java.util.Objects.requireNonNull(resourceFactory, "resourceFactory");
        sampler = gpu.descriptorHeap().allocateSamplers(1);
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gpu.descriptorHeap().writer().writeSampler(sampler, 0,
                        VkSamplerCreateInfo.calloc(stack).sType$Default()
                                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                                .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                                .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                                .minLod(0f).maxLod(Float.MAX_VALUE));
            }
            samplerOwner = resourceFactory.create(sampler::destroy);
        } catch (RuntimeException | Error failure) {
            sampler.destroy();
            throw failure;
        }
    }

    /** Prepares an immutable fallback-safe epoch and starts its asynchronous texture upload. */
    public synchronized PreparedUpload prepareUpload(MinecraftMaterialLookup lookup,
            Consumer<? super GpuComputeCompletion> completion) {
        requireOpen();
        MinecraftMaterialUpload upload = new MinecraftMaterialUpload(
                gpu, compute, this, lookup, completion);
        return new PreparedUpload(new PreparedEpoch(lookup, upload.epoch()), upload.job());
    }

    /**
     * Takes ownership of an uploaded image lease for every CPU texture and creates one immutable program
     * epoch. The caller publishes its shader data with the matching geometry IDs and closes the epoch when
     * that atomic program registration is displaced.
     */
    synchronized Epoch createPreparedEpoch(MinecraftMaterialLookup lookup,
                                           List<? extends UploadedImage> images) {
        requireOpen();
        java.util.Objects.requireNonNull(lookup, "lookup");
        List<UploadedImage> ownedImages = List.copyOf(images);
        List<MinecraftMaterialRecord> fallbackRecords;
        try {
            if (ownedImages.size() != lookup.textures().size()) {
                throw new IllegalArgumentException("uploaded image count must match the epoch CPU texture count");
            }
            validateTextureOrdinals(lookup.records(), ownedImages.size());
            fallbackRecords = fallbackRecords(lookup.records().size());
        } catch (RuntimeException | Error failure) {
            Throwable cleanup = null;
            for (UploadedImage image : ownedImages) cleanup = release(cleanup, image::close);
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
        Epoch epoch = createEpoch(fallbackRecords, ownedImages);
        return epoch;
    }

    static List<MinecraftMaterialRecord> fallbackRecords(int materialCount) {
        if (materialCount <= 0) throw new IllegalArgumentException("materialCount must be positive");
        return java.util.Collections.nCopies(materialCount, MinecraftMaterialRecord.fallback());
    }

    public synchronized void populateMaterialRecords(Epoch epoch, MinecraftMaterialLookup lookup) {
        requireOpen();
        java.util.Objects.requireNonNull(epoch, "epoch");
        java.util.Objects.requireNonNull(lookup, "lookup");
        epoch.requireWritable();
        validateTextureOrdinals(lookup.records(), epoch.images.size());
        writeMaterialRecords(epoch.materialTable.mapped().order(ByteOrder.LITTLE_ENDIAN),
                lookup.records(), epoch.firstDescriptor);
        epoch.materialTable.flush(0L,
                Math.multiplyExact((long) lookup.records().size(), MinecraftMaterialData.BYTE_SIZE));
    }

    public synchronized void seal(Epoch epoch) {
        requireOpen();
        java.util.Objects.requireNonNull(epoch, "epoch").seal();
    }

    /** Creates the nonzero fallback root used before the first resource-pack upload. */
    public synchronized Epoch createFallbackEpoch() {
        requireOpen();
        Epoch epoch = createEpoch(List.of(MinecraftMaterialRecord.fallback()), List.of());
        epoch.seal();
        return epoch;
    }

    private Epoch createEpoch(List<MinecraftMaterialRecord> records, List<UploadedImage> images) {
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        VmaMappedBuffer materialTable = null;
        VmaMappedBuffer implementation = null;
        VmaMappedBuffer primitive = null;
        VmaMappedBuffer instance = null;
        Epoch epoch = null;
        try {
            if (!images.isEmpty()) {
                descriptors = gpu.descriptorHeap().allocateResources(images.size());
                writeDescriptors(descriptors, images);
            }
            int firstDescriptor = descriptors == null ? 0 : descriptors.firstIndex().value();
            long tableSize = Math.multiplyExact((long) records.size(), MinecraftMaterialData.BYTE_SIZE);
            materialTable = createAsync(tableSize,
                    bytes -> writeMaterialRecords(bytes, records, firstDescriptor));
            VmaMappedBuffer table = materialTable;
            implementation = create(MinecraftImplementationData.BYTE_SIZE,
                    bytes -> new MinecraftImplementationData(table.deviceRange().address().value(),
                            new MinecraftImplementationData.SamplerIndex(sampler.firstIndex().value()), 0).write(bytes));
            primitive = create(MinecraftPrimitiveData.BYTE_SIZE, bytes -> new MinecraftPrimitiveData(
                    new MinecraftPrimitiveData.Float2[0], new MinecraftPrimitiveData.Float4[0],
                    new MinecraftPrimitiveData.Float3(1.0f, 1.0f, 1.0f), 0,
                    new MinecraftPrimitiveData.SampledTexture2DIndex(0),
                    new MinecraftPrimitiveData.SamplerIndex(sampler.firstIndex().value()), 0, 0.0f,
                    new MinecraftPrimitiveData.Float3(1.0f, 0.0f, 0.0f),
                    new MinecraftPrimitiveData.Float3(0.0f, 0.0f, 1.0f)).write(bytes));
            instance = create(MinecraftInstanceData.BYTE_SIZE, bytes -> new MinecraftInstanceData(
                    new MinecraftInstanceData.Float3(1.0f, 1.0f, 1.0f), 0,
                    new MinecraftInstanceData.SampledTexture2DIndex(0), 0.0f).write(bytes));
            epoch = new Epoch(descriptors, materialTable, implementation, primitive, instance,
                    firstDescriptor, images);
            epoch.owner = resourceFactory.create(epoch::destroy);
            return epoch;
        } catch (RuntimeException | Error failure) {
            Throwable cleanup = null;
            if (epoch != null && epoch.owner != null) {
                cleanup = release(cleanup, epoch.owner::close);
            } else {
                if (implementation != null) cleanup = release(cleanup, implementation::close);
                if (materialTable != null) cleanup = release(cleanup, materialTable::close);
                if (primitive != null) cleanup = release(cleanup, primitive::close);
                if (instance != null) cleanup = release(cleanup, instance::close);
                if (descriptors != null) cleanup = release(cleanup, descriptors::destroy);
                for (UploadedImage image : images) cleanup = release(cleanup, image::close);
                if (epoch != null) cleanup = release(cleanup, epoch.samplerLease::close);
            }
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
    }

    private static void writeMaterialRecords(ByteBuffer bytes, List<MinecraftMaterialRecord> records,
                                             int firstDescriptor) {
        for (int index = 0; index < records.size(); index++) {
            ByteBuffer destination = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            destination.position(Math.multiplyExact(index, MinecraftMaterialData.BYTE_SIZE));
            destination.limit(destination.position() + MinecraftMaterialData.BYTE_SIZE);
            records.get(index).shaderData(ordinal -> Math.addExact(firstDescriptor, ordinal))
                    .write(destination.slice().order(ByteOrder.LITTLE_ENDIAN));
        }
    }

    private void writeDescriptors(GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                                  List<UploadedImage> images) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo.Buffer views = VkImageViewCreateInfo.calloc(images.size(), stack);
            VkImageDescriptorInfoEXT.Buffer imageDescriptors = VkImageDescriptorInfoEXT.calloc(images.size(), stack);
            List<GpuDescriptorWriter.ImageWrite> writes = new ArrayList<>(images.size());
            for (int index = 0; index < images.size(); index++) {
                UploadedImage image = images.get(index);
                VkImageViewCreateInfo view = views.get(index).sType$Default()
                        .image(image.image()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(image.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(image.mipLevels()).baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT info = imageDescriptors.get(index).sType$Default()
                        .pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                writes.add(new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, info));
            }
            gpu.descriptorHeap().writer().writeImages(descriptors, 0, writes);
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
        if (ordinal < 0 || ordinal >= textureCount) {
            throw new IllegalArgumentException("material texture ordinal exceeds the uploaded image batch");
        }
    }

    private VmaMappedBuffer create(long size, Writer writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(
                gpu, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Minecraft program buffer");
        try {
            writer.write(buffer.mapped().order(ByteOrder.LITTLE_ENDIAN));
            buffer.flush(0, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private VmaMappedBuffer createAsync(long size, Writer writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.createAsync(
                gpu, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Minecraft async program buffer");
        try {
            writer.write(buffer.mapped().order(ByteOrder.LITTLE_ENDIAN));
            buffer.flush(0, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Minecraft program resources are closed");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        samplerOwner.close();
    }

    /** Uploader-owned image lease transferred into exactly one material epoch. */
    public interface UploadedImage extends AutoCloseable {
        long image();
        int format();
        int mipLevels();
        @Override void close();
    }

    /** CPU lookup and fallback-safe shader roots prepared for one resource-pack epoch. */
    public record PreparedEpoch(MinecraftMaterialLookup lookup, Epoch gpu) {
        public PreparedEpoch {
            java.util.Objects.requireNonNull(lookup, "lookup");
            java.util.Objects.requireNonNull(gpu, "gpu");
        }
    }

    /** Prepared epoch plus its cancellable asynchronous initialization. */
    public record PreparedUpload(PreparedEpoch epoch, GpuComputeJob job) {
        public PreparedUpload {
            java.util.Objects.requireNonNull(epoch, "epoch");
            java.util.Objects.requireNonNull(job, "job");
        }
    }

    /** Immutable root and all resource-pack GPU resources captured by one program registration. */
    public final class Epoch implements AutoCloseable {
        private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
        private final VmaMappedBuffer materialTable;
        private final VmaMappedBuffer implementation;
        private final VmaMappedBuffer primitive;
        private final VmaMappedBuffer instance;
        private final int firstDescriptor;
        private final List<UploadedImage> images;
        private final ResourceOwner samplerLease;
        private ResourceOwner owner;
        private boolean sealed;
        private boolean closed;
        private Epoch(GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                      VmaMappedBuffer materialTable, VmaMappedBuffer implementation,
                      VmaMappedBuffer primitive, VmaMappedBuffer instance,
                      int firstDescriptor, List<UploadedImage> images) {
            this.descriptors = descriptors;
            this.materialTable = materialTable;
            this.implementation = implementation;
            this.primitive = primitive;
            this.instance = instance;
            this.firstDescriptor = firstDescriptor;
            this.images = images;
            this.samplerLease = samplerOwner.retain();
        }
        private synchronized void seal() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            if (sealed) return;

            sealed = true;
        }
        private synchronized void requireWritable() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            if (sealed) throw new IllegalStateException("Minecraft material epoch is already immutable");
        }
        public synchronized ShaderData<MinecraftProgramTypes.ImplementationData> implementationData() {
            requirePublished();
            return MinecraftProgramTypes.IMPLEMENTATION_DATA.data(
                    implementation.deviceRange().address().value(), owner.reference());
        }
        long materialTableBuffer() { return materialTable.buffer(); }
        public synchronized ShaderData<MinecraftProgramTypes.PrimitiveData> fallbackBindingData() {
            requirePublished();
            return MinecraftProgramTypes.PRIMITIVE_DATA.data(
                    primitive.deviceRange().address().value(), owner.reference());
        }
        public synchronized ShaderData<MinecraftProgramTypes.InstanceData> fallbackInstanceData() {
            requirePublished();
            return MinecraftProgramTypes.INSTANCE_DATA.data(
                    instance.deviceRange().address().value(), owner.reference());
        }
        /** Strong ownership transferred to an accepted upload or another asynchronous consumer. */
        public ResourceOwner retain() { return owner.retain(); }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.close();
        }
        private void requirePublished() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            if (!sealed) throw new IllegalStateException("Minecraft material epoch is not published");
        }
        private void destroy() {
            Throwable failure = MinecraftProgramResources.release(null, implementation::close);
            failure = MinecraftProgramResources.release(failure, materialTable::close);
            failure = MinecraftProgramResources.release(failure, primitive::close);
            failure = MinecraftProgramResources.release(failure, instance::close);
            if (descriptors != null) failure = MinecraftProgramResources.release(failure, descriptors::destroy);
            for (UploadedImage image : images) {
                failure = MinecraftProgramResources.release(failure, image::close);
            }
            failure = MinecraftProgramResources.release(failure, samplerLease::close);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("Minecraft material destruction failed", failure);
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

    @FunctionalInterface private interface Writer { void write(ByteBuffer destination); }
}
