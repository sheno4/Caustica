package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftImplementationData;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftMaterialData;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Session-wide sampler and factory for immutable Minecraft program/material epochs. */
public final class MinecraftProgramResources implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftProgramResources.class);
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

    /** Prepares an immutable fallback-safe epoch and its one-shot texture transfer pass. */
    public synchronized PreparedUpload prepareUpload(MinecraftMaterialLookup lookup, Runnable submitted) {
        requireOpen();
        MinecraftMaterialUploadPass pass = new MinecraftMaterialUploadPass(
                gpu, this, lookup, submitted);
        return new PreparedUpload(new PreparedEpoch(lookup, pass.epoch()), pass);
    }

    /**
     * Takes ownership of an uploaded image lease for every CPU texture and creates one immutable program
     * epoch. The caller publishes {@link Epoch#implementationData()} with the matching geometry IDs and
     * gives {@link Epoch#retirement()} to that atomic program registration's retirement path.
     */
    synchronized Epoch createPreparedEpoch(MinecraftMaterialLookup lookup,
                                           List<? extends UploadedImage> images) {
        requireOpen();
        java.util.Objects.requireNonNull(lookup, "lookup");
        List<UploadedImage> ownedImages = List.copyOf(images);
        if (ownedImages.size() != lookup.textures().size()) {
            throw new IllegalArgumentException("uploaded image count must match the epoch CPU texture count");
        }
        try {
            validateTextureOrdinals(lookup.records(), ownedImages.size());
            List<MinecraftMaterialRecord> fallbackRecords = fallbackRecords(lookup.records().size());
            Epoch epoch = createEpoch(fallbackRecords, ownedImages);
            liveEpochs++;
            return epoch;
        } catch (RuntimeException | Error failure) {
            throw failure;
        }
    }

    static List<MinecraftMaterialRecord> fallbackRecords(int materialCount) {
        if (materialCount <= 0) throw new IllegalArgumentException("materialCount must be positive");
        return java.util.Collections.nCopies(materialCount, MinecraftMaterialRecord.fallback());
    }

    synchronized void publishMaterialRecords(Epoch epoch, MinecraftMaterialLookup lookup) {
        requireOpen();
        java.util.Objects.requireNonNull(epoch, "epoch");
        java.util.Objects.requireNonNull(lookup, "lookup");
        validateTextureOrdinals(lookup.records(), epoch.images.size());
        writeMaterialRecords(epoch.materialTable.mapped().order(ByteOrder.LITTLE_ENDIAN),
                lookup.records(), epoch.firstDescriptor);
        epoch.materialTable.flush(0L,
                Math.multiplyExact((long) lookup.records().size(), MinecraftMaterialData.BYTE_SIZE));
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
        VmaMappedBuffer materialTable = null;
        VmaMappedBuffer implementation = null;
        VmaMappedBuffer primitive = null;
        VmaMappedBuffer instance = null;
        try {
            if (!images.isEmpty()) {
                descriptors = gpu.descriptorHeap().allocateResources(images.size(), "Minecraft material textures");
                writeDescriptors(descriptors, images);
            }
            int firstDescriptor = descriptors == null ? 0 : descriptors.firstIndex().value();
            long tableSize = Math.multiplyExact((long) records.size(), MinecraftMaterialData.BYTE_SIZE);
            materialTable = create(tableSize,
                    bytes -> writeMaterialRecords(bytes, records, firstDescriptor));
            VmaMappedBuffer table = materialTable;
            implementation = create(MinecraftImplementationData.BYTE_SIZE,
                    bytes -> new MinecraftImplementationData(table.deviceRange().address().value(),
                            new MinecraftImplementationData.SamplerIndex(sampler.firstIndex().value()), 0).write(bytes));
            primitive = create(MinecraftPrimitiveData.BYTE_SIZE, bytes -> new MinecraftPrimitiveData(
                    new MinecraftPrimitiveData.Float2[0], new MinecraftPrimitiveData.Float4[0],
                    new MinecraftPrimitiveData.Float3(1.0f, 1.0f, 1.0f), 0,
                    new MinecraftPrimitiveData.SampledTexture2DIndex(0), 0, 0.0f,
                    new MinecraftPrimitiveData.Float3(1.0f, 0.0f, 0.0f),
                    new MinecraftPrimitiveData.Float3(0.0f, 0.0f, 1.0f)).write(bytes));
            instance = create(MinecraftInstanceData.BYTE_SIZE, bytes -> new MinecraftInstanceData(
                    new MinecraftInstanceData.Float3(1.0f, 1.0f, 1.0f), 0,
                    new MinecraftInstanceData.SampledTexture2DIndex(0), 0.0f).write(bytes));
            return new Epoch(descriptors, materialTable, implementation, primitive, instance,
                    firstDescriptor, images);
        } catch (RuntimeException | Error failure) {
            if (instance != null) instance.close();
            if (primitive != null) primitive.close();
            if (implementation != null) implementation.close();
            if (materialTable != null) materialTable.close();
            if (descriptors != null) descriptors.destroy();
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

    /** CPU lookup and fallback-safe shader roots prepared for one resource-pack epoch. */
    public record PreparedEpoch(MinecraftMaterialLookup lookup, Epoch gpu) {
        public PreparedEpoch {
            java.util.Objects.requireNonNull(lookup, "lookup");
            java.util.Objects.requireNonNull(gpu, "gpu");
        }
    }

    /** Prepared epoch plus the pass that initializes its texture images. */
    public record PreparedUpload(PreparedEpoch epoch, Pass<PassFrame> pass) {
        public PreparedUpload {
            java.util.Objects.requireNonNull(epoch, "epoch");
            java.util.Objects.requireNonNull(pass, "pass");
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
        private final Runnable retirement = this::retire;
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
        }
        public ShaderData<MinecraftProgramTypes.ImplementationData> implementationData() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            return MinecraftProgramTypes.IMPLEMENTATION_DATA.data(
                    implementation.deviceRange().address().value());
        }
        long materialTableBuffer() { return materialTable.buffer(); }
        public ShaderData<MinecraftProgramTypes.PrimitiveData> fallbackBindingData() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            return MinecraftProgramTypes.PRIMITIVE_DATA.data(primitive.deviceRange().address().value());
        }
        public ShaderData<MinecraftProgramTypes.InstanceData> fallbackInstanceData() {
            if (closed) throw new IllegalStateException("Minecraft material epoch is retired");
            return MinecraftProgramTypes.INSTANCE_DATA.data(instance.deviceRange().address().value());
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
                LOGGER.error(
                        "Minecraft material epoch retirement cleanup failed", failure);
            }
        }
        private synchronized Throwable release() {
            if (closed) return null;
            closed = true;
            Throwable failure = MinecraftProgramResources.release(null, implementation::close);
            failure = MinecraftProgramResources.release(failure, primitive::close);
            failure = MinecraftProgramResources.release(failure, instance::close);
            failure = MinecraftProgramResources.release(failure, materialTable::close);
            if (descriptors != null) failure = MinecraftProgramResources.release(failure, descriptors::destroy);
            try {
                for (UploadedImage image : images) {
                    failure = MinecraftProgramResources.release(failure, image::close);
                }
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

    @FunctionalInterface private interface Writer { void write(ByteBuffer destination); }
}
