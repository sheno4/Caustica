package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;

/** Host-mapped VMA upload owner for retained Minecraft terrain buffers. */
public final class MinecraftVulkanTerrainUploader implements MinecraftTerrainUploader {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVulkanTerrainUploader.class);
    private static final int TEXTURE_PRESENT = 1;
    private final GpuDevice gpu;
    private final MinecraftPrograms programs;
    private final SharedAtlas atlas;
    private boolean closed;

    public MinecraftVulkanTerrainUploader(GpuDevice gpu, MinecraftPrograms programs,
                                          BorrowedMinecraftTexture blockAtlas) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.programs = java.util.Objects.requireNonNull(programs, "programs");
        atlas = SharedAtlas.create(gpu, java.util.Objects.requireNonNull(blockAtlas, "blockAtlas"));
    }

    @Override public UploadedSection upload(MinecraftTerrainMesh source) {
        java.util.Objects.requireNonNull(source, "source");
        float[] positions = source.positions();
        int[] indices = source.indices();
        float[] cornerUvs = source.cornerUvs();
        float[] primitive = source.primitiveData();
        SharedAtlas.Lease atlasLease;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Minecraft terrain uploader is closed");
            atlasLease = atlas.retain();
        }
        VmaMappedBuffer positionsBuffer = null;
        VmaMappedBuffer indicesBuffer = null;
        VmaMappedBuffer primitiveBuffer = null;
        VmaMappedBuffer instanceBuffer = null;
        try {
            positionsBuffer = create((long) positions.length * Float.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (float value : positions) bytes.putFloat(value); });
            indicesBuffer = create((long) indices.length * Integer.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (int index : indices) bytes.putInt(index); });
            primitiveBuffer = create((long) source.triangleCount() * MinecraftPrimitiveData.BYTE_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    bytes -> writePrimitiveRecords(bytes, source.triangleCount(), cornerUvs, primitive,
                            atlas.descriptorIndex()));
            instanceBuffer = create(MinecraftInstanceData.BYTE_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    bytes -> new MinecraftInstanceData(new MinecraftInstanceData.Float3(1f, 1f, 1f), 0,
                            new MinecraftInstanceData.SampledTexture2DIndex(0), 0f).write(bytes));
            return uploaded(source, programs, positionsBuffer, indicesBuffer, primitiveBuffer, instanceBuffer,
                    atlasLease);
        } catch (RuntimeException | Error failure) {
            Throwable cleanup = closeAll(instanceBuffer, primitiveBuffer, indicesBuffer, positionsBuffer, atlasLease);
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
    }

    static long primitiveRecordOffset(int firstIndex) {
        return Math.multiplyExact((long) firstIndex / 3L, MinecraftPrimitiveData.BYTE_SIZE);
    }

    static List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries(
            MinecraftTerrainMesh source, MinecraftPrograms programs, VulkanDeviceAddress primitiveAddress) {
        List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = new ArrayList<>();
        for (MinecraftTerrainMesh.Geometry geometry : source.geometries()) {
            long primitiveOffset = primitiveRecordOffset(geometry.firstIndex());
            ShaderData<MinecraftProgramTypes.PrimitiveData> binding = MinecraftProgramTypes.PRIMITIVE_DATA.data(
                    primitiveAddress.addBytes(primitiveOffset).value());
            var policy = geometry.coverage() == MinecraftTerrainMesh.Coverage.OPAQUE
                    ? (MeshBuild.CoveragePolicy) new MeshBuild.CoveragePolicy.Opaque()
                    : new MeshBuild.CoveragePolicy.Cutout(geometry.alphaCutoff());
            var surface = switch (geometry.program()) {
                case MATERIAL -> new MeshBuild.SurfaceSlot<>(programs.materialSurface(), binding, policy);
                case WATER -> new MeshBuild.SurfaceSlot<>(programs.waterSurface(), binding, policy);
                case PORTAL -> new MeshBuild.SurfaceSlot<>(programs.portalSurface(), binding, policy);
            };
            var volume = geometry.program() == MinecraftTerrainMesh.ProgramCategory.WATER
                    ? new MeshBuild.VolumeSlot<>(programs.waterVolume(), binding) : null;
            geometries.add(new MeshBuild.Geometry<>(surface, volume, geometry.firstIndex(), geometry.indexCount()));
        }
        return List.copyOf(geometries);
    }

    private UploadedSection uploaded(MinecraftTerrainMesh source, MinecraftPrograms programs,
                                     VmaMappedBuffer positions, VmaMappedBuffer indices,
                                     VmaMappedBuffer primitive, VmaMappedBuffer instance,
                                     SharedAtlas.Lease atlasLease) {
        List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = geometries(
                source, programs, primitive.deviceRange().address());
        MeshBuild<MinecraftProgramTypes.InstanceData> build = new MeshBuild<>(
                new MeshBuild.Stream(positions.deviceRange(), 12), null,
                new MeshBuild.Stream(indices.deviceRange(), 4), source.vertexCount(),
                new MeshBuild.IndexRevision(source.indexRevision()), geometries);
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData =
                MinecraftProgramTypes.INSTANCE_DATA.data(instance.deviceRange().address().value());
        return new Uploaded(build, instanceData, positions, indices, primitive, instance, atlasLease);
    }

    static void writePrimitiveRecords(ByteBuffer bytes, int triangles, float[] uvs, float[] primitive,
                                      int atlasDescriptor) {
        for (int triangle = 0; triangle < triangles; triangle++) {
            int uv = triangle * 6;
            int data = triangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS;
            var uvValues = new MinecraftPrimitiveData.Float2[]{
                    new MinecraftPrimitiveData.Float2(uvs[uv], uvs[uv + 1]),
                    new MinecraftPrimitiveData.Float2(uvs[uv + 2], uvs[uv + 3]),
                    new MinecraftPrimitiveData.Float2(uvs[uv + 4], uvs[uv + 5])};
            var white = new MinecraftPrimitiveData.Float4(1f, 1f, 1f, 1f);
            boolean textured = primitive[data + MinecraftTerrainMesh.PRIMITIVE_ATLAS_PRESENT_OFFSET] != 0.0f;
            var record = new MinecraftPrimitiveData(uvValues, new MinecraftPrimitiveData.Float4[]{white, white, white},
                    new MinecraftPrimitiveData.Float3(primitive[data + 4], primitive[data + 5], primitive[data + 6]),
                    (int) primitive[data + 8],
                    new MinecraftPrimitiveData.SampledTexture2DIndex(textured ? atlasDescriptor : 0),
                    textured ? TEXTURE_PRESENT : 0,
                    primitive[data + 3],
                    new MinecraftPrimitiveData.Float3(0f, 0f, 0f),
                    new MinecraftPrimitiveData.Float3(0f, 0f, 0f));
            record.write(bytes.slice(triangle * MinecraftPrimitiveData.BYTE_SIZE,
                    MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
        }
        bytes.position(triangles * MinecraftPrimitiveData.BYTE_SIZE);
    }

    private VmaMappedBuffer create(long size, int extraUsage, Writer writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(gpu, size, extraUsage, "Minecraft terrain upload");
        try {
            ByteBuffer bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            buffer.flush(0L, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        atlas.close();
    }

    private record Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                            ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                            VmaMappedBuffer positions, VmaMappedBuffer indices, VmaMappedBuffer primitive,
                            VmaMappedBuffer instance, SharedAtlas.Lease atlasLease)
            implements UploadedSection {
        @Override public void close() {
            closeAll(instance, primitive, indices, positions, atlasLease);
        }
    }

    static final class SharedAtlas implements AutoCloseable {
        private final int descriptorIndex;
        private final Runnable cleanup;
        private int references = 1;
        private boolean ownerClosed;

        SharedAtlas(int descriptorIndex, Runnable cleanup) {
            if (descriptorIndex < 0) throw new IllegalArgumentException("descriptorIndex must be non-negative");
            this.descriptorIndex = descriptorIndex;
            this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
        }

        static SharedAtlas create(GpuDevice gpu, BorrowedMinecraftTexture borrowed) {
            GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                range = gpu.descriptorHeap().allocateResources(1);
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(borrowed.vkImage()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(borrowed.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(borrowed.baseMipLevel()).levelCount(borrowed.mipLevels())
                        .baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(borrowed.imageLayout());
                gpu.descriptorHeap().writer().writeResource(range, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(image)));
                GpuDescriptorRange<GpuDescriptorIndex.Resource> ownedRange = range;
                return new SharedAtlas(range.firstIndex().value(),
                        () -> throwIfFailed(closeAll(ownedRange::destroy, borrowed)));
            } catch (RuntimeException | Error failure) {
                Throwable cleanup = closeAll(range == null ? null : range::destroy, borrowed);
                if (cleanup != null) failure.addSuppressed(cleanup);
                throw failure;
            }
        }

        int descriptorIndex() {
            return descriptorIndex;
        }

        synchronized Lease retain() {
            if (ownerClosed) throw new IllegalStateException("Minecraft terrain atlas is closed");
            references++;
            return new Lease(this);
        }

        @Override public synchronized void close() {
            if (ownerClosed) return;
            ownerClosed = true;
            release();
        }

        private synchronized void release() {
            if (--references == 0) {
                try {
                    cleanup.run();
                } catch (Throwable failure) {
                    LOGGER.error("Minecraft terrain atlas cleanup failed", failure);
                }
            }
        }

        static final class Lease implements AutoCloseable {
            private SharedAtlas owner;

            private Lease(SharedAtlas owner) {
                this.owner = owner;
            }

            @Override public synchronized void close() {
                SharedAtlas current = owner;
                if (current == null) return;
                owner = null;
                current.release();
            }
        }
    }

    private static Throwable closeAll(AutoCloseable... values) {
        Throwable failure = null;
        for (AutoCloseable value : values) {
            if (value == null) continue;
            try {
                value.close();
            } catch (Throwable next) {
                if (failure == null) failure = next;
                else failure.addSuppressed(next);
            }
        }
        return failure;
    }

    private static void throwIfFailed(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(failure);
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
