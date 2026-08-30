package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Host-mapped VMA upload owner for retained Minecraft terrain buffers. */
public final class MinecraftVulkanTerrainUploader implements MinecraftTerrainUploader {
    private final GpuDevice gpu;
    private final MinecraftPrograms programs;

    public MinecraftVulkanTerrainUploader(GpuDevice gpu, MinecraftPrograms programs) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.programs = java.util.Objects.requireNonNull(programs, "programs");
    }

    @Override public UploadedSection upload(MinecraftTerrainMesh source) {
        float[] positions = source.positions();
        int[] indices = source.indices();
        float[] cornerUvs = source.cornerUvs();
        float[] primitive = source.primitiveData();
        VmaMappedBuffer positionsBuffer = create((long) positions.length * Float.BYTES,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                bytes -> { for (float value : positions) bytes.putFloat(value); });
        VmaMappedBuffer indicesBuffer = null;
        VmaMappedBuffer primitiveBuffer = null;
        VmaMappedBuffer instanceBuffer = null;
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
            if (instanceBuffer != null) instanceBuffer.close();
            if (primitiveBuffer != null) primitiveBuffer.close();
            if (indicesBuffer != null) indicesBuffer.close();
            positionsBuffer.close();
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
                                     VmaMappedBuffer primitive, VmaMappedBuffer instance) {
        List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = geometries(
                source, programs, primitive.deviceRange().address());
        MeshBuild<MinecraftProgramTypes.InstanceData> build = new MeshBuild<>(
                new MeshBuild.Stream(positions.deviceRange(), 12), null,
                new MeshBuild.Stream(indices.deviceRange(), 4), source.vertexCount(),
                new MeshBuild.IndexRevision(source.indexRevision()), geometries);
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData =
                MinecraftProgramTypes.INSTANCE_DATA.data(instance.deviceRange().address().value());
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

    private record Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                            ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                            VmaMappedBuffer positions, VmaMappedBuffer indices,
                            VmaMappedBuffer primitive, VmaMappedBuffer instance)
            implements UploadedSection {
        @Override public void close() {
            instance.close();
            primitive.close();
            indices.close();
            positions.close();
        }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
