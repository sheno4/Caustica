package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftTerrainGeometryTest {
    @Test
    void replacementIsOneAtomicMeshAndPlacementBatchAndRetiresTheUpload() {
        var channel = new RecordingChannel();
        var upload = new Uploaded(0x1000L);
        var terrain = new MinecraftTerrainGeometry(channel, new SceneId() { }, ignored -> upload);

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(7L, 16, -32, 48, mesh())));

        assertEquals(1, channel.batches.size());
        var batch = channel.batches.getFirst();
        assertInstanceOf(GeometryChannel.SetMesh.class, batch.operations().get(0));
        var placement = assertInstanceOf(GeometryChannel.SetInstance.class, batch.operations().get(1));
        assertEquals(16.0, placement.transform().translationX());
        assertEquals(-32.0, placement.transform().translationY());
        assertEquals(48.0, placement.transform().translationZ());
        assertFalse(upload.closed);
        batch.retired().run();
        assertTrue(upload.closed);
    }

    @Test
    void transactionCoalescesASectionAndDropsMeshAndPlacementTogether() {
        var channel = new RecordingChannel();
        var terrain = new MinecraftTerrainGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x2000L));
        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh())));

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh()),
                new MinecraftTerrainGeometry.Drop(2L)));

        var operations = channel.batches.getLast().operations();
        assertEquals(2, operations.size());
        assertInstanceOf(GeometryChannel.DropInstance.class, operations.get(0));
        assertInstanceOf(GeometryChannel.DropMesh.class, operations.get(1));
    }

    @Test
    void malformedCpuGeometryIsRejectedBeforeUpload() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftTerrainMesh(
                new float[]{0, 0, 0}, new int[]{0, 0, 0}, new float[0],
                new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS],
                List.of(new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                        MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f, null, material())), 1L));
    }

    @Test
    void rejectedCloseKeepsSectionStateSoCloseCanBeRetried() {
        var channel = new RecordingChannel();
        var terrain = new MinecraftTerrainGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x3000L));
        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(9L, 0, 0, 0, mesh())));
        channel.rejectNext = true;

        assertThrows(IllegalArgumentException.class, terrain::close);
        terrain.close();

        assertEquals(2, channel.batches.size());
        assertEquals(2, channel.batches.getLast().operations().size());
    }

    @Test
    void primitiveUploadMatchesTheShaderRecordStrideAndCarriesUvTintAndEmission() {
        ByteBuffer bytes = ByteBuffer.allocate(MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        float[] primitive = new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS];
        primitive[3] = 0.75f;
        primitive[4] = 0.25f;
        primitive[5] = 0.5f;
        primitive[6] = 1f;
        primitive[8] = 19f;

        MinecraftVulkanTerrainUploader.writePrimitiveRecords(bytes, 1,
                new float[]{0, 0, 1, 0, 0, 1}, primitive);

        assertEquals(MinecraftPrimitiveData.BYTE_SIZE, bytes.position());
        assertEquals(1f, bytes.getFloat(8));
        assertEquals(0.25f, bytes.getFloat(80));
        assertEquals(0.5f, bytes.getFloat(84));
        assertEquals(1f, bytes.getFloat(88));
        assertEquals(19, bytes.getInt(92));
        assertEquals(0.75f, bytes.getFloat(104));
        assertEquals(2L * MinecraftPrimitiveData.BYTE_SIZE,
                MinecraftVulkanTerrainUploader.primitiveRecordOffset(6));
    }

    @Test
    void multiProgramTerrainWaitsForNativeMultiGeometryPacking() {
        var first = new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f, null, material());
        var second = new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.WATER,
                MinecraftTerrainMesh.Coverage.OPAQUE, 3, 3, 0.5f, null, material());
        var source = new MinecraftTerrainMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 2, 1}, new float[12],
                new float[2 * MinecraftTerrainMesh.PRIMITIVE_FLOATS], List.of(first, second), 0L);

        assertThrows(IllegalStateException.class,
                () -> MinecraftVulkanTerrainUploader.requireBackendShape(source));
    }

    private static MinecraftTerrainMesh mesh() {
        return new MinecraftTerrainMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                new float[6], new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS],
                List.of(new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                        MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f, null, material())), 3L);
    }

    private static MinecraftTerrainMesh.MaterialBinding material() {
        return new MinecraftTerrainMesh.MaterialBinding(7, ResourceId.of("minecraft", "stone"),
                ResourceId.of("minecraft", "textures/atlas/blocks.png"));
    }

    private static final class Uploaded implements MinecraftTerrainUploader.UploadedSection {
        private final long address;
        private boolean closed;

        private Uploaded(long address) { this.address = address; }

        @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
            var positions = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), 36), 12);
            var indices = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address + 0x100), 12), 4);
            var surface = new MeshBuild.SurfaceSlot<>(new dev.comfyfluffy.caustica.api.program.SurfaceId<
                    MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { },
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(address + 0x200),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, null, indices, 3, new MeshBuild.IndexRevision(3),
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }

        @Override public dev.comfyfluffy.caustica.api.program.ShaderData<
                MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(address + 0x300);
        }

        @Override public void close() { closed = true; }
    }

    private static final class RecordingChannel implements GeometryChannel {
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        private boolean rejectNext;

        @Override public <N> MeshId<N> newMesh(ShaderDataType<N> instanceDataType) { return new MeshId<>() { }; }
        @Override public InstanceId newInstance() { return new InstanceId() { }; }
        @Override public void submit(RetainedBatch<Operation> batch) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalArgumentException("rejected");
            }
            batches.add(batch);
        }
    }
}
