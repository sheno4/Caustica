package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftEntityGeometryTest {
    @Test
    void replacementAndPlacementAreOneAtomicBatch() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded first = new Uploaded(0x1000L);
        Uploaded second = new Uploaded(0x2000L);
        var uploads = new ArrayList<>(List.of(first, second));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 7);

        geometry.put(key, mesh(), GeometryTransform.translation(10, 20, 30), 0xff);
        geometry.put(key, mesh(), GeometryTransform.translation(10, 20, 30), 0xff);

        assertEquals(2, channel.batches.size());
        var initial = channel.batches.get(0).operations();
        var replacement = channel.batches.get(1).operations();
        assertEquals(2, initial.size());
        assertEquals(2, replacement.size());
        var initialMesh = assertInstanceOf(GeometryChannel.SetMesh.class, initial.get(0));
        var initialPlacement = assertInstanceOf(GeometryChannel.SetInstance.class, initial.get(1));
        var replacementMesh = assertInstanceOf(GeometryChannel.SetMesh.class, replacement.get(0));
        var replacementPlacement = assertInstanceOf(GeometryChannel.SetInstance.class, replacement.get(1));
        assertSame(initialMesh.mesh(), replacementMesh.mesh());
        assertSame(initialPlacement.instance(), replacementPlacement.instance());
        assertEquals(GeometryTransform.translation(10, 20, 30), replacementPlacement.transform());
    }

    @Test
    void transformKeepsUploadAliveUntilTheReplacementPlacementRetires() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded uploaded = new Uploaded(0x3000L);
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploaded);
        var key = new MinecraftEntityGeometry.Key(1, 9);
        geometry.put(key, mesh(), GeometryTransform.translation(0, 0, 0), 0xff);

        geometry.transform(key, GeometryTransform.translation(1, 2, 3), 0x01);

        var operation = assertInstanceOf(GeometryChannel.SetInstance.class,
                channel.batches.get(1).operations().getFirst());
        assertEquals(GeometryTransform.translation(1, 2, 3), operation.transform());
        assertEquals(0x01, operation.mask());
        channel.batches.getFirst().retired().run();
        assertFalse(uploaded.closed);
        channel.batches.get(1).retired().run();
        assertTrue(uploaded.closed);
    }

    @Test
    void removalDropsPlacementAndMeshTogether() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x4000L));
        var key = new MinecraftEntityGeometry.Key(2, 11);
        geometry.put(key, mesh(), GeometryTransform.translation(0, 0, 0), 0xff);

        geometry.drop(key);

        var removal = channel.batches.get(1).operations();
        assertEquals(2, removal.size());
        assertInstanceOf(GeometryChannel.DropInstance.class, removal.get(0));
        assertInstanceOf(GeometryChannel.DropMesh.class, removal.get(1));
    }

    @Test
    void rejectedRemovalLeavesTheResidentRetryable() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x5000L));
        var key = new MinecraftEntityGeometry.Key(1, 12);
        geometry.put(key, mesh(), GeometryTransform.translation(0, 0, 0), 0xff);
        channel.rejectNext = true;

        assertThrows(IllegalStateException.class, () -> geometry.drop(key));
        geometry.drop(key);

        assertEquals(2, channel.batches.size());
        assertInstanceOf(GeometryChannel.DropInstance.class, channel.batches.get(1).operations().getFirst());
    }

    @Test
    void rejectedStopDoesNotOrphanLiveResidents() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x6000L));
        var key = new MinecraftEntityGeometry.Key(1, 13);
        geometry.put(key, mesh(), GeometryTransform.translation(0, 0, 0), 0xff);
        channel.rejectNext = true;

        assertThrows(IllegalStateException.class, geometry::stop);
        geometry.transform(key, GeometryTransform.translation(4, 5, 6), 0xff);
        geometry.stop();

        assertEquals(3, channel.batches.size());
        assertInstanceOf(GeometryChannel.SetInstance.class, channel.batches.get(1).operations().getFirst());
        assertInstanceOf(GeometryChannel.DropInstance.class, channel.batches.get(2).operations().getFirst());
    }

    private static MinecraftEntityMesh mesh() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE,
                0, 0, 1, 0, 1, 1, 1);
        return new MinecraftEntityMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2}, new float[] {0, 0, 1, 0, 0, 1}, List.of(triangle), 17);
    }

    private static final class Uploaded implements MinecraftEntityUploader.UploadedEntity {
        final long address;
        boolean closed;

        Uploaded(long address) { this.address = address; }

        @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
            var positions = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), 36), 12);
            var indices = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address + 0x100), 12), 4);
            var surface = new MeshBuild.SurfaceSlot<>(new SurfaceId<
                    MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { },
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(address + 0x200),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, null, indices, 3, new MeshBuild.IndexRevision(17),
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }

        @Override public dev.comfyfluffy.caustica.api.program.ShaderData<
                MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(address + 0x300);
        }

        @Override public void close() { closed = true; }
    }

    private static final class RecordingChannel implements GeometryChannel {
        final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        boolean rejectNext;

        @Override public <N> MeshId<N> newMesh(dev.comfyfluffy.caustica.api.program.ShaderDataType<N> type) {
            return new MeshId<>() { };
        }

        @Override public InstanceId newInstance() { return new InstanceId() { }; }

        @Override public void submit(RetainedBatch<Operation> batch) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected");
            }
            batches.add(batch);
        }
    }
}
