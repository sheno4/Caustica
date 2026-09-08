package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtInstanceTablePlanTest {
    private static final ShaderDataType<Object> DATA = ShaderDataType.create("instance-table");
    private static final SurfaceId<Object, Object> SURFACE = new SurfaceId<>() { };
    private static final GeometryTransform IDENTITY = GeometryTransform.translation(0, 0, 0);

    @Test
    void hashPreservesBothFullWidthKeyComponentsAndUnsignedWrap() {
        assertEquals(0x89025cc1, RtInstanceTablePlan.hash(1, 0));
        assertEquals(0x658eec67, RtInstanceTablePlan.hash(1, 1));
        assertEquals(0x50a72ab7, RtInstanceTablePlan.hash(0x1_0000_0001L, 3));
        assertEquals(0x1b652c20, RtInstanceTablePlan.hash(-1, 0));
        assertEquals(0xbf22dc37, RtInstanceTablePlan.hash(1, 0x1_0000_0000L));
    }

    @Test
    void instanceAndPlacementIdentitySurviveCollisionsAndUnrelatedTableGrowth() {
        var builder = new RtInstanceTablePlan.Builder();
        MeshBuild<?> mesh = mesh(0x1000, 12, 8, new MeshBuild.IndexRevision(1), 0, 6);
        List<RtInstanceTablePlan.Input> inputs = new ArrayList<>();
        long bucket = RtInstanceTablePlan.hash(1, 0) & 7;
        for (long identity = 1; inputs.size() < 3; identity++) {
            if ((RtInstanceTablePlan.hash(identity, 0) & 7) == bucket) {
                inputs.add(input(identity, 0, mesh));
            }
        }
        var first = builder.build(inputs);
        assertEquals(8, first.capacity());
        for (int i = 0; i < inputs.size(); i++) {
            long identity = inputs.get(i).identity();
            assertEquals((bucket + i) & 7, first.slot(identity, 0));
            assertEquals(identity, first.record(first.slot(identity, 0)).identity());
            assertEquals(-1, first.slot(identity, 1));
        }
        for (int ordinal = 1; ordinal <= 6; ordinal++) inputs.add(input(1, ordinal, mesh));
        var expanded = builder.build(inputs);
        assertEquals(32, expanded.capacity());
        for (var input : inputs) {
            var record = expanded.record(expanded.slot(input.identity(), input.placementOrdinal()));
            assertEquals(input.identity(), record.identity());
            assertEquals(input.placementOrdinal(), record.placementOrdinal());
        }
        assertEquals(-1, expanded.slot(100_000, 0));
        assertEquals(-1, expanded.slot(0, 0));
        assertEquals(8, first.capacity());
    }

    @Test
    void exactMeshRevisionUsesBuildIdentityIncludingNullIndexRevision() {
        var builder = new RtInstanceTablePlan.Builder();
        MeshBuild<?> first = mesh(0x1000, 12, 8, null, 0, 6);
        MeshBuild<?> equal = mesh(0x1000, 12, 8, null, 0, 6);
        assertEquals(first, equal);
        assertNotSame(first, equal);
        var a = builder.build(List.of(input(1, 0, first)));
        var anotherReadyMeshForSameBuild = builder.build(List.of(input(2, 0, first)));
        var distinctBuild = builder.build(List.of(input(1, 0, equal)));
        var recordA = record(a, 1, 0);
        assertNotEquals(0, recordA.meshRevision());
        assertEquals(recordA.meshRevision(), record(anotherReadyMeshForSameBuild, 2, 0).meshRevision());
        assertNotEquals(recordA.meshRevision(), record(distinctBuild, 1, 0).meshRevision());
        assertEquals(0, recordA.topologyToken());
        assertEquals(0, record(distinctBuild, 1, 0).topologyToken());
    }

    @Test
    void emptyRevisionsPreserveTokensHeldByEarlierTables() {
        var builder = new RtInstanceTablePlan.Builder();
        var revision = new MeshBuild.IndexRevision(7);
        var original = mesh(0x1000, 12, 8, revision, 0, 6);
        var a = builder.build(List.of(input(1, 0, original)));
        var empty = builder.build(List.of());
        assertEquals(-1, empty.slot(1, 0));
        var different = builder.build(List.of(input(1, 0,
                mesh(0x2000, 24, 9, revision, 0, 6))));
        var sameBuild = builder.build(List.of(input(1, 0, original)));
        var compatibleBuild = builder.build(List.of(input(1, 0,
                mesh(0x3000, 24, 8, revision, 0, 6))));
        assertEquals(record(a, 1, 0).meshRevision(), record(sameBuild, 1, 0).meshRevision());
        assertEquals(record(a, 1, 0).topologyToken(), record(compatibleBuild, 1, 0).topologyToken());
        assertNotEquals(record(a, 1, 0).meshRevision(), record(compatibleBuild, 1, 0).meshRevision());
        assertNotEquals(record(a, 1, 0).topologyToken(), record(different, 1, 0).topologyToken());
    }

    @Test
    void previousTableReusesOnlyExactMeshAndEqualInstanceValues() {
        var builder = new RtInstanceTablePlan.Builder();
        var mesh = mesh(0x1000, 12, 8, new MeshBuild.IndexRevision(1), 0, 6);
        var original = builder.build(List.of(input(1, 0, mesh), input(2, 0, mesh)));
        var equivalent = builder.build(List.of(input(1, 0, mesh), input(2, 0, mesh)), original);
        assertSame(original, equivalent);
        var moved = builder.build(List.of(new RtInstanceTablePlan.Input(1, 0, mesh,
                GeometryTransform.translation(3, 0, 0), 0), input(2, 0, mesh)), original);
        assertNotSame(record(original, 1, 0), record(moved, 1, 0));
        assertSame(record(original, 2, 0), record(moved, 2, 0));
        assertTrue(moved.sameSlotAssignments(original));
        var differentData = builder.build(List.of(new RtInstanceTablePlan.Input(1, 0, mesh,
                IDENTITY, 7), input(2, 0, mesh)), original);
        assertNotSame(record(original, 1, 0), record(differentData, 1, 0));
        assertTrue(differentData.sameSlotAssignments(original));
        var equalMesh = mesh(0x1000, 12, 8, new MeshBuild.IndexRevision(1), 0, 6);
        assertEquals(mesh, equalMesh);
        var replacementMesh = builder.build(List.of(input(1, 0, equalMesh), input(2, 0, mesh)), original);
        assertNotSame(record(original, 1, 0), record(replacementMesh, 1, 0));
        assertNotEquals(record(original, 1, 0).meshRevision(), record(replacementMesh, 1, 0).meshRevision());
        assertTrue(replacementMesh.sameSlotAssignments(original));
        assertEquals(0, record(original, 1, 0).instanceData());
        assertEquals(IDENTITY, record(original, 1, 0).transform());
    }

    @Test
    void slotAssignmentsCompareEveryKeyAndEmptySlotRatherThanOnlyHashes() {
        var builder = new RtInstanceTablePlan.Builder();
        var mesh = mesh(0x1000, 12, 8, null, 0, 6);
        long collision = 2;
        while ((RtInstanceTablePlan.hash(1, 0) & 3) != (RtInstanceTablePlan.hash(collision, 0) & 3)) collision++;
        var first = builder.build(List.of(input(1, 0, mesh), input(collision, 0, mesh)));
        var swapped = builder.build(List.of(input(collision, 0, mesh), input(1, 0, mesh)), first);
        assertFalse(first.sameSlotAssignments(swapped));
        assertSame(record(first, 1, 0), record(swapped, 1, 0));
        assertSame(record(first, collision, 0), record(swapped, collision, 0));
        var replaced = builder.build(List.of(input(1, 0, mesh), input(collision, 1, mesh)), first);
        assertFalse(first.sameSlotAssignments(replaced));
        assertFalse(first.sameSlotAssignments(builder.build(List.of(input(1, 0, mesh)), first)));
        assertFalse(first.sameSlotAssignments(null));
        assertTrue(builder.build(List.of()).sameSlotAssignments(builder.build(List.of())));
    }

    @Test
    void canonicalTopologyMatchesCompleteEngineCompatibilityContract() {
        var builder = new RtInstanceTablePlan.Builder();
        var revision = new MeshBuild.IndexRevision(7);
        List<MeshBuild<?>> meshes = List.of(
                mesh(0x1000, 12, 8, revision, 0, 6),
                mesh(0x2000, 24, 8, revision, 0, 6),
                mesh(0x2000, 24, 9, revision, 0, 6),
                mesh(0x2000, 24, 8, new MeshBuild.IndexRevision(8), 0, 6),
                mesh(0x2000, 24, 8, revision, 3, 6),
                mesh(0x2000, 24, 8, revision, 0, 9),
                mesh(0x2000, 24, 8, revision, 0, 3, 3, 3),
                mesh(0x2000, 24, 8, null, 0, 6));
        List<RtInstanceTablePlan> tables = meshes.stream()
                .map(mesh -> builder.build(List.of(input(1, 0, mesh)))).toList();
        for (int previous = 0; previous < meshes.size(); previous++) {
            for (int current = 0; current < meshes.size(); current++) {
                long previousToken = record(tables.get(previous), 1, 0).topologyToken();
                long currentToken = record(tables.get(current), 1, 0).topologyToken();
                assertEquals(RetainedSceneSnapshot.vertexTopologyCompatible(meshes.get(previous), meshes.get(current)),
                        previousToken != 0 && previousToken == currentToken,
                        "previous=" + previous + ", current=" + current);
            }
        }
        assertEquals(12, record(tables.get(0), 1, 0).positionStride());
        assertEquals(24, record(tables.get(1), 1, 0).positionStride());
    }

    @Test
    void preparedOrAbandonedIntermediateTablesDoNotChangeCurrentOrSubmittedHistory() {
        var builder = new RtInstanceTablePlan.Builder();
        var revision = new MeshBuild.IndexRevision(7);
        var a = builder.build(List.of(new RtInstanceTablePlan.Input(7, 0,
                mesh(0x1000, 12, 8, revision, 0, 6), GeometryTransform.translation(10, 0, 0), 101)));
        var b = builder.build(List.of(new RtInstanceTablePlan.Input(7, 0,
                mesh(0x2000, 16, 8, revision, 0, 6), GeometryTransform.translation(20, 0, 0), 102)));
        var currentMesh = mesh(0x3000, 24, 8, revision, 0, 6);
        var c = builder.build(List.of(new RtInstanceTablePlan.Input(7, 0,
                currentMesh, GeometryTransform.translation(30, 0, 0), 103)));

        assertSame(record(a, 7, 0), a.record(a.slot(7, 0)));
        assertEquals(0x1000, record(a, 7, 0).positionAddress());
        assertEquals(10, record(a, 7, 0).transform().translationX());
        assertEquals(0x3000, record(c, 7, 0).positionAddress());
        assertEquals(30, record(c, 7, 0).transform().translationX());
        assertNotEquals(record(a, 7, 0).meshRevision(), record(c, 7, 0).meshRevision());
        assertEquals(record(a, 7, 0).topologyToken(), record(c, 7, 0).topologyToken());
        assertEquals(0x2000, record(b, 7, 0).positionAddress());

        var changedProgramOrReadyMesh = builder.build(List.of(new RtInstanceTablePlan.Input(7, 0,
                currentMesh, GeometryTransform.translation(30, 0, 0), 103)));
        assertEquals(record(c, 7, 0).meshRevision(), record(changedProgramOrReadyMesh, 7, 0).meshRevision());
        assertEquals(record(c, 7, 0).topologyToken(), record(changedProgramOrReadyMesh, 7, 0).topologyToken());
        assertSame(record(c, 7, 0), c.record(c.slot(7, 0)));
        assertEquals(-1, c.slot(7, 1));
        assertEquals(-1, c.slot(8, 0));
    }

    @Test
    void reflectedRecordsPackCurrentDataIndependentOfTheHistoryOrigin() {
        var builder = new RtInstanceTablePlan.Builder();
        var originA = new SceneOrigin(30_000_000.25, -30_000_000.25, 100.25);
        var originC = new SceneOrigin(30_000_016.25, -30_000_032.25, 104.25);
        var mesh = mesh(0x1122334455660000L, 24, 8, new MeshBuild.IndexRevision(7), 0, 6);
        var table = builder.build(List.of(new RtInstanceTablePlan.Input(0x1_0000_0001L, 3, mesh,
                GeometryTransform.translation(30_000_000.5, -30_000_000.75, 101), 0xfedcba9876543210L)));
        var packedA = pack(table, originA);
        var packedC = pack(table, originC);
        int slot = table.slot(0x1_0000_0001L, 3);
        int base = slot * RtInstanceTablePlan.RECORD_BYTES;
        assertEquals(112, RtInstanceTablePlan.RECORD_BYTES);
        assertEquals(0x1_0000_0001L, packedA.getLong(base));
        assertEquals(3, packedA.getLong(base + 8));
        assertEquals(record(table, 0x1_0000_0001L, 3).meshRevision(), packedA.getLong(base + 16));
        assertEquals(record(table, 0x1_0000_0001L, 3).topologyToken(), packedA.getLong(base + 24));
        assertEquals(mesh.positions().bytes().address().value(), packedA.getLong(base + 32));
        assertEquals(0xfedcba9876543210L, packedA.getLong(base + 40));
        assertEquals(24, packedA.getInt(base + 48));
        assertEquals(0, packedA.getInt(base + 52));
        assertEquals(0, packedA.getLong(base + 56));
        assertEquals(0.25f, packedA.getFloat(base + 76));
        assertEquals(-0.5f, packedA.getFloat(base + 92));
        assertEquals(0.75f, packedA.getFloat(base + 108));
        assertEquals(packedC.getFloat(base + 76), packedA.getFloat(base + 76) + (float) (originA.x() - originC.x()));
        assertEquals(packedC.getFloat(base + 92), packedA.getFloat(base + 92) + (float) (originA.y() - originC.y()));
        assertEquals(packedC.getFloat(base + 108), packedA.getFloat(base + 108) + (float) (originA.z() - originC.z()));
        for (int i = 0; i < table.capacity(); i++) {
            if (i == slot) continue;
            for (int offset = 0; offset < RtInstanceTablePlan.RECORD_BYTES; offset++) {
                assertEquals(0, packedA.get(i * RtInstanceTablePlan.RECORD_BYTES + offset));
            }
        }
    }

    @Test
    void emptyTableStillHasAnEmptySlotAndRejectsAmbiguousLiveKeys() {
        var builder = new RtInstanceTablePlan.Builder();
        var empty = builder.build(List.of());
        assertEquals(1, empty.capacity());
        assertEquals(0, empty.mask());
        assertEquals(-1, empty.slot(1, 0));
        ByteBuffer packed = pack(empty, SceneOrigin.ZERO);
        while (packed.hasRemaining()) assertEquals(0, packed.get());
        var mesh = mesh(0x1000, 12, 8, null, 0, 6);
        assertThrows(IllegalArgumentException.class, () -> input(0, 0, mesh));
        assertThrows(IllegalArgumentException.class, () -> input(1, -1, mesh));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(input(1, 0, mesh), input(1, 0, mesh))));
    }

    private static RtInstanceTablePlan.InstanceRecord record(RtInstanceTablePlan table, long identity, long ordinal) {
        return table.record(table.slot(identity, ordinal));
    }

    private static RtInstanceTablePlan.Input input(long identity, long ordinal, MeshBuild<?> mesh) {
        return new RtInstanceTablePlan.Input(identity, ordinal, mesh, IDENTITY, 0);
    }

    private static ByteBuffer pack(RtInstanceTablePlan table, SceneOrigin origin) {
        ByteBuffer buffer = ByteBuffer.allocate(table.byteSize()).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) buffer.put((byte) 0x5a);
        buffer.clear();
        table.write(buffer, origin);
        assertEquals(table.byteSize(), buffer.position());
        return buffer.flip();
    }

    private static MeshBuild<Object> mesh(long address, int stride, int vertices,
                                          MeshBuild.IndexRevision revision, int... slices) {
        var slot = new MeshBuild.SurfaceSlot<>(SURFACE, DATA.data(17), new MeshBuild.CoveragePolicy.Opaque());
        List<MeshBuild.Geometry<Object>> geometries = new ArrayList<>();
        for (int i = 0; i < slices.length; i += 2) {
            geometries.add(new MeshBuild.Geometry<>(slot, null, slices[i], slices[i + 1]));
        }
        return new MeshBuild<>(stream(address, 4096, stride), stream(0x4000, 4096, 4), vertices,
                revision, MeshBuild.BuildPolicy.REFITTABLE, geometries);
    }

    private static MeshBuild.Stream stream(long address, int size, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), size),
                stride, ResourceOwner.none());
    }
}
