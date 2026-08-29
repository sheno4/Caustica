package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftPrimitiveData;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/** Host-mapped VMA upload owner for retained Minecraft terrain buffers. */
public final class MinecraftVulkanTerrainUploader implements MinecraftTerrainUploader {
    private final GpuDevice gpu;
    private final MinecraftPrograms programs;

    public MinecraftVulkanTerrainUploader(GpuDevice gpu, MinecraftPrograms programs) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.programs = java.util.Objects.requireNonNull(programs, "programs");
    }

    @Override public UploadedSection upload(MinecraftTerrainMesh source) {
        requireBackendShape(source);
        float[] positions = source.positions();
        int[] indices = source.indices();
        float[] cornerUvs = source.cornerUvs();
        float[] primitive = source.primitiveData();
        Buffer positionsBuffer = create((long) positions.length * Float.BYTES,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                bytes -> { for (float value : positions) bytes.putFloat(value); });
        Buffer indicesBuffer = null;
        Buffer primitiveBuffer = null;
        Buffer instanceBuffer = null;
        try {
            indicesBuffer = create((long) indices.length * Integer.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (int index : indices) bytes.putInt(index); });
            primitiveBuffer = create((long) source.triangleCount() * MinecraftPrimitiveData.BYTE_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    bytes -> writePrimitiveRecords(bytes, source.triangleCount(), cornerUvs, primitive));
            instanceBuffer = create(MinecraftInstanceData.BYTE_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    bytes -> new MinecraftInstanceData(new MinecraftInstanceData.Float3(1f, 1f, 1f), 0,
                            new MinecraftInstanceData.SampledTexture2DIndex(0), 0f).write(bytes));
            return uploaded(source, programs, positionsBuffer, indicesBuffer, primitiveBuffer, instanceBuffer);
        } catch (RuntimeException | Error failure) {
            if (instanceBuffer != null) instanceBuffer.destroy();
            if (primitiveBuffer != null) primitiveBuffer.destroy();
            if (indicesBuffer != null) indicesBuffer.destroy();
            positionsBuffer.destroy();
            throw failure;
        }
    }

    static void requireBackendShape(MinecraftTerrainMesh source) {
        if (source.geometries().size() != 1) {
            throw new IllegalStateException("Minecraft terrain publication requires native multi-geometry MeshBuild support");
        }
    }

    static long primitiveRecordOffset(int firstIndex) {
        return Math.multiplyExact((long) firstIndex / 3L, MinecraftPrimitiveData.BYTE_SIZE);
    }

    private UploadedSection uploaded(MinecraftTerrainMesh source, MinecraftPrograms programs,
                                     Buffer positions, Buffer indices, Buffer primitive, Buffer instance) {
        List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = new ArrayList<>();
        for (MinecraftTerrainMesh.Geometry geometry : source.geometries()) {
            long primitiveOffset = primitiveRecordOffset(geometry.firstIndex());
            ShaderData<MinecraftProgramTypes.PrimitiveData> binding =
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(primitive.addressAt(primitiveOffset));
            var policy = geometry.coverage() == MinecraftTerrainMesh.Coverage.OPAQUE
                    ? (MeshBuild.CoveragePolicy) new MeshBuild.CoveragePolicy.Opaque()
                    : new MeshBuild.CoveragePolicy.Cutout(geometry.alphaCutoff(), geometry.opacityMicromap() == null
                    ? null : new MeshBuild.OpacityMicromapHint(geometry.opacityMicromap().transparentAlpha(),
                    geometry.opacityMicromap().opaqueAlpha(), geometry.opacityMicromap().subdivisionLevel()));
            var surface = switch (geometry.program()) {
                case MATERIAL -> new MeshBuild.SurfaceSlot<>(programs.materialSurface(), binding, policy);
                case WATER -> new MeshBuild.SurfaceSlot<>(programs.waterSurface(), binding, policy);
                case PORTAL -> new MeshBuild.SurfaceSlot<>(programs.portalSurface(), binding, policy);
            };
            var volume = geometry.program() == MinecraftTerrainMesh.ProgramCategory.WATER
                    ? new MeshBuild.VolumeSlot<>(programs.waterVolume(), binding) : null;
            geometries.add(new MeshBuild.Geometry<>(surface, volume, geometry.firstIndex(), geometry.indexCount()));
        }
        MeshBuild<MinecraftProgramTypes.InstanceData> build = new MeshBuild<>(positions.stream(12), null,
                indices.stream(4), source.vertexCount(), new MeshBuild.IndexRevision(source.indexRevision()), geometries);
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData =
                MinecraftProgramTypes.INSTANCE_DATA.data(instance.address);
        return new Uploaded(build, instanceData, positions, indices, primitive, instance);
    }

    static void writePrimitiveRecords(ByteBuffer bytes, int triangles, float[] uvs, float[] primitive) {
        for (int triangle = 0; triangle < triangles; triangle++) {
            int uv = triangle * 6;
            int data = triangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS;
            var uvValues = new MinecraftPrimitiveData.Float2[]{
                    new MinecraftPrimitiveData.Float2(uvs[uv], uvs[uv + 1]),
                    new MinecraftPrimitiveData.Float2(uvs[uv + 2], uvs[uv + 3]),
                    new MinecraftPrimitiveData.Float2(uvs[uv + 4], uvs[uv + 5])};
            var white = new MinecraftPrimitiveData.Float4(1f, 1f, 1f, 1f);
            var record = new MinecraftPrimitiveData(uvValues, new MinecraftPrimitiveData.Float4[]{white, white, white},
                    new MinecraftPrimitiveData.Float3(primitive[data + 4], primitive[data + 5], primitive[data + 6]),
                    (int) primitive[data + 8], new MinecraftPrimitiveData.SampledTexture2DIndex(0), 0,
                    primitive[data + 3],
                    new MinecraftPrimitiveData.Float3(0f, 0f, 0f),
                    new MinecraftPrimitiveData.Float3(0f, 0f, 0f));
            record.write(bytes.slice(triangle * MinecraftPrimitiveData.BYTE_SIZE,
                    MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
        }
        bytes.position(triangles * MinecraftPrimitiveData.BYTE_SIZE);
    }

    private Buffer create(long size, int extraUsage, Writer writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // These buffers are populated by the host before their first queue use, so exclusive sharing
            // has no queue-family transfer. The retained build's first submission establishes queue ownership.
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | extraUsage)
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
                throw new IllegalStateException("terrain buffer is not mapped and device-addressable");
            }
            ByteBuffer bytes = MemoryUtil.memByteBuffer(outInfo.pMappedData(), Math.toIntExact(size))
                    .order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            Vma.vmaFlushAllocation(gpu.vmaAllocator(), allocation, 0, size);
            return new Buffer(handle, allocation, address, size);
        }
    }

    private record Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                            ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                            Buffer positions, Buffer indices, Buffer primitive, Buffer instance)
            implements UploadedSection {
        @Override public void close() {
            instance.destroy();
            primitive.destroy();
            indices.destroy();
            positions.destroy();
        }
    }

    private final class Buffer {
        private final long handle;
        private final long allocation;
        private final long address;
        private final long size;
        private boolean destroyed;

        private Buffer(long handle, long allocation, long address, long size) {
            this.handle = handle;
            this.allocation = allocation;
            this.address = address;
            this.size = size;
        }

        private MeshBuild.Stream stream(int stride) {
            return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), size), stride);
        }

        private long addressAt(long byteOffset) {
            if (byteOffset < 0L || byteOffset >= size) throw new IllegalArgumentException("buffer offset is outside range");
            return Math.addExact(address, byteOffset);
        }

        private void destroy() {
            if (destroyed) return;
            Vma.vmaDestroyBuffer(gpu.vmaAllocator(), handle, allocation);
            destroyed = true;
        }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
