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
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
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
import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;

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
            closeAfterFailure(failure, sampler::destroy);
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
            closeAfterFailure(failure, ownedImages.stream().<Runnable>map(image -> image::close)
                    .toArray(Runnable[]::new));
            throw failure;
        }
        return createEpoch(fallbackRecords, ownedImages);
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
        validateTextureOrdinals(lookup.records(), epoch.textureCount);
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
        var releases = new ArrayList<Runnable>();
        images.forEach(image -> releases.add(image::close));
        try {
            var samplerLease = samplerOwner.retain();
            releases.addFirst(samplerLease::close);
            int firstDescriptor;
            if (!images.isEmpty()) {
                var descriptors = gpu.descriptorHeap().allocateResources(images.size());
                releases.add(descriptors::destroy);
                writeDescriptors(descriptors, images);
                firstDescriptor = descriptors.firstIndex().value();
            } else {
                firstDescriptor = 0;
            }
            long tableSize = Math.multiplyExact((long) records.size(), MinecraftMaterialData.BYTE_SIZE);
            var materialTable = createAsync(tableSize,
                    bytes -> writeMaterialRecords(bytes, records, firstDescriptor));
            releases.add(materialTable::close);
            var implementation = create(MinecraftImplementationData.BYTE_SIZE,
                    bytes -> new MinecraftImplementationData(materialTable.deviceRange().address().value(),
                            new MinecraftImplementationData.SamplerIndex(sampler.firstIndex().value()), 0).write(bytes));
            releases.add(implementation::close);
            var primitive = create(MinecraftPrimitiveData.BYTE_SIZE, bytes -> new MinecraftPrimitiveData(
                    new MinecraftPrimitiveData.Float2[0], new MinecraftPrimitiveData.Float4[0],
                    new MinecraftPrimitiveData.Float3(1.0f, 1.0f, 1.0f), 0,
                    new MinecraftPrimitiveData.SampledTexture2DIndex(0),
                    new MinecraftPrimitiveData.SamplerIndex(sampler.firstIndex().value()), 0, 0.0f,
                    new MinecraftPrimitiveData.Float3(1.0f, 0.0f, 0.0f),
                    new MinecraftPrimitiveData.Float3(0.0f, 0.0f, 1.0f)).write(bytes));
            releases.add(primitive::close);
            var instance = create(MinecraftInstanceData.BYTE_SIZE, bytes -> new MinecraftInstanceData(
                    new MinecraftInstanceData.Float3(1.0f, 1.0f, 1.0f), 0,
                    new MinecraftInstanceData.SampledTexture2DIndex(0), 0.0f).write(bytes));
            releases.add(instance::close);
            var allocation = new ResourceLifetime(releases.reversed().toArray(Runnable[]::new));
            var owner = resourceFactory.create(allocation::close);
            // The registered owner now controls native destruction. Rollback releases producer claims.
            releases.clear();
            releases.add(owner::close);
            var implementationData = MinecraftProgramTypes.IMPLEMENTATION_DATA.data(
                    implementation.deviceRange().address().value(), owner);
            releases.add(implementationData::close);
            var bindingData = MinecraftProgramTypes.PRIMITIVE_DATA.data(
                    primitive.deviceRange().address().value(), owner);
            releases.add(bindingData::close);
            var instanceData = MinecraftProgramTypes.INSTANCE_DATA.data(
                    instance.deviceRange().address().value(), owner);
            releases.add(instanceData::close);
            return new Epoch(materialTable, firstDescriptor, images.size(), owner,
                    implementationData, bindingData, instanceData,
                    new ResourceLifetime(releases.reversed().toArray(Runnable[]::new)));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, releases.reversed().toArray(Runnable[]::new));
            throw failure;
        }
    }

    private static void writeMaterialRecords(ByteBuffer bytes, List<MinecraftMaterialRecord> records,
                                             int firstDescriptor) {
        for (int index = 0; index < records.size(); index++) {
            ByteBuffer destination = bytes.slice(Math.multiplyExact(index, MinecraftMaterialData.BYTE_SIZE),
                    MinecraftMaterialData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            records.get(index).shaderData(ordinal -> Math.addExact(firstDescriptor, ordinal))
                    .write(destination);
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
            closeAfterFailure(failure, buffer::close);
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
            closeAfterFailure(failure, buffer::close);
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
    public static final class Epoch implements AutoCloseable {
        private final VmaMappedBuffer materialTable;
        private final int firstDescriptor;
        private final int textureCount;
        private final ResourceOwner owner;
        private final ShaderData<MinecraftProgramTypes.ImplementationData> implementationData;
        private final ShaderData<MinecraftProgramTypes.PrimitiveData> bindingData;
        private final ShaderData<MinecraftProgramTypes.InstanceData> instanceData;
        private final ResourceLifetime lifetime;
        private boolean sealed;
        private boolean closed;

        private Epoch(VmaMappedBuffer materialTable, int firstDescriptor, int textureCount,
                      ResourceOwner owner,
                      ShaderData<MinecraftProgramTypes.ImplementationData> implementationData,
                      ShaderData<MinecraftProgramTypes.PrimitiveData> bindingData,
                      ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                      ResourceLifetime lifetime) {
            this.materialTable = materialTable;
            this.firstDescriptor = firstDescriptor;
            this.textureCount = textureCount;
            this.owner = owner;
            this.implementationData = implementationData;
            this.bindingData = bindingData;
            this.instanceData = instanceData;
            this.lifetime = lifetime;
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
        /** Borrowed from this epoch; consumers retain their own copy before returning. */
        public synchronized ShaderData<MinecraftProgramTypes.ImplementationData> implementationData() {
            requirePublished();
            return implementationData;
        }
        long materialTableBuffer() { return materialTable.buffer(); }
        /** Borrowed from this epoch; consumers retain their own copy before returning. */
        public synchronized ShaderData<MinecraftProgramTypes.PrimitiveData> fallbackBindingData() {
            requirePublished();
            return bindingData;
        }
        /** Borrowed from this epoch; consumers retain their own copy before returning. */
        public synchronized ShaderData<MinecraftProgramTypes.InstanceData> fallbackInstanceData() {
            requirePublished();
            return instanceData;
        }
        /** Acquire an independent epoch claim for an upload or another asynchronous consumer. */
        public ResourceOwner retain() { return owner.retain(); }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            lifetime.close();
        }
        private void requirePublished() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            if (!sealed) throw new IllegalStateException("Minecraft material epoch is not published");
        }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer destination); }
}
