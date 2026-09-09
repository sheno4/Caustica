package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtPackedInstancePagesTest {
    private static final SceneOrigin ORIGIN = new SceneOrigin(30_000_000.25, 2, 3);

    @Test
    void valueEquivalentInputsReuseTheWholeTableAndPackedDirectory() {
        var builder = new RtInstanceTablePlan.Builder();
        var packed = new RtPackedInstancePages();
        var mesh = mesh();
        var table = builder.build(inputs(300, mesh));
        var before = packed.resolve(table, ORIGIN);
        var equivalent = builder.build(inputs(300, mesh), table);
        var after = packed.resolve(equivalent, new SceneOrigin(30_000_000.25, 2, 3));
        assertSame(table, equivalent);
        assertSame(before, after);
        assertEquals(8, after.size());
        for (ByteBuffer page : after) {
            assertTrue(page.isReadOnly());
            assertEquals(0, page.position());
            assertEquals(128 * RtInstanceTablePlan.RECORD_BYTES, page.remaining());
        }
        assertPacked(table, ORIGIN, after);
    }

    @Test
    void oneTransformAndDataEditRepacksOnlyItsPageWithoutChangingGeometrySlots() {
        var builder = new RtInstanceTablePlan.Builder();
        var packed = new RtPackedInstancePages();
        var source = inputs(300, mesh());
        var table = builder.build(source);
        var before = packed.resolve(table, ORIGIN);
        var changed = new ArrayList<>(source);
        var old = changed.get(170);
        changed.set(170, new RtInstanceTablePlan.Input(old.identity(), old.placementOrdinal(), old.mesh(),
                GeometryTransform.translation(30_000_001.5, 4, 5), 0x5566));
        var current = builder.build(changed, table);
        var after = packed.resolve(current, ORIGIN);
        int changedPage = current.slot(old.identity(), old.placementOrdinal()) / RtPackedInstancePages.RECORDS_PER_PAGE;
        assertTrue(current.sameSlotAssignments(table));
        for (int page = 0; page < after.size(); page++) {
            if (page == changedPage) assertNotSame(before.get(page), after.get(page));
            else assertSame(before.get(page), after.get(page));
        }
        assertPacked(current, ORIGIN, after);
        assertPacked(table, ORIGIN, before);
    }

    @Test
    void removalZeroesTheVacatedSlotAndDoesNotMutateEarlierPackedPages() {
        var builder = new RtInstanceTablePlan.Builder();
        var packed = new RtPackedInstancePages();
        var source = inputs(300, mesh());
        var table = builder.build(source);
        var before = packed.resolve(table, ORIGIN);
        var removed = source.stream().filter(input -> {
            int slot = table.slot(input.identity(), input.placementOrdinal());
            return table.record((slot + 1) & table.mask()) == null;
        }).findFirst().orElseThrow();
        int slot = table.slot(removed.identity(), removed.placementOrdinal());
        var remaining = source.stream().filter(input -> input != removed).toList();
        var current = builder.build(remaining, table);
        assertEquals(table.capacity(), current.capacity());
        assertFalse(current.sameSlotAssignments(table));
        assertNull(current.record(slot));
        var after = packed.resolve(current, ORIGIN);
        ByteBuffer combined = combine(after);
        for (int offset = 0; offset < RtInstanceTablePlan.RECORD_BYTES; offset++) {
            assertEquals(0, combined.get(slot * RtInstanceTablePlan.RECORD_BYTES + offset));
        }
        assertEquals(removed.identity(), combine(before).getLong(slot * RtInstanceTablePlan.RECORD_BYTES));
        assertPacked(current, ORIGIN, after);
        assertPacked(table, ORIGIN, before);
    }

    @Test
    void originChangesGrowthAndEmptyTablesPackTheirExactCurrentExtent() {
        var builder = new RtInstanceTablePlan.Builder();
        var packed = new RtPackedInstancePages();
        var mesh = mesh();
        var initial = builder.build(inputs(100, mesh));
        var first = packed.resolve(initial, ORIGIN);
        var rebasedOrigin = new SceneOrigin(30_000_016.25, 18, 19);
        var rebased = packed.resolve(initial, rebasedOrigin);
        for (int page = 0; page < rebased.size(); page++) assertNotSame(first.get(page), rebased.get(page));
        assertPacked(initial, rebasedOrigin, rebased);
        var grown = builder.build(inputs(300, mesh), initial);
        assertFalse(initial.sameSlotAssignments(grown));
        assertPacked(grown, rebasedOrigin, packed.resolve(grown, rebasedOrigin));
        var empty = builder.build(List.of(), grown);
        var emptyPages = packed.resolve(empty, rebasedOrigin);
        assertEquals(1, emptyPages.size());
        assertEquals(RtInstanceTablePlan.RECORD_BYTES, emptyPages.getFirst().remaining());
        assertPacked(empty, rebasedOrigin, emptyPages);
        var returned = packed.resolve(initial, ORIGIN);
        assertNotSame(first.getFirst(), returned.getFirst());
        assertPacked(initial, ORIGIN, returned);
    }

    @Test
    void rebasingInstanceBytesPreservesResidentGeometryBindings() {
        var table = new RtInstanceTablePlan.Builder().build(inputs(1, mesh()));
        var packed = new RtPackedInstancePages();
        var slot = new RtRetainedSceneBackend.TraceSlot(null, null, null, null, null);
        slot.setInstanceTable(table);
        var batch = new RtRetainedSceneBackend.TraceBatch(new RtRetainedSceneBackend.TracePagePlan[0]);
        var residency = slot.batch(batch, batch);
        Object pipeline = new Object(), lights = new Object(), identity = new Object();
        var range = new RtStableTraceRanges().reserve(1, 0);
        var page = slot.page(range, residency);
        int instanceIndex = table.slot(1, 1);
        residency.written(pipeline, lights, slot.instanceAssignments);
        page.geometryWritten(identity, 0, 0, instanceIndex);
        var before = packed.resolve(table, ORIGIN);

        var after = packed.resolve(table, new SceneOrigin(30_000_016.25, 18, 19));
        slot.setInstanceTable(table);

        assertNotEquals(combine(before), combine(after));
        assertTrue(residency.hasGeometry(pipeline, slot.instanceAssignments));
        assertTrue(page.hasGeometry(identity, 0, 0, table.slot(1, 1)));
        assertPacked(table, ORIGIN, before);
    }

    private static void assertPacked(RtInstanceTablePlan table, SceneOrigin origin, List<ByteBuffer> pages) {
        ByteBuffer sequential = ByteBuffer.allocate(table.byteSize()).order(ByteOrder.LITTLE_ENDIAN);
        table.write(sequential, origin);
        sequential.flip();
        assertEquals(sequential, combine(pages));
    }

    private static ByteBuffer combine(List<ByteBuffer> pages) {
        ByteBuffer result = ByteBuffer.allocate(pages.stream().mapToInt(ByteBuffer::remaining).sum())
                .order(ByteOrder.LITTLE_ENDIAN);
        for (ByteBuffer page : pages) result.put(page.duplicate());
        return result.flip();
    }

    private static List<RtInstanceTablePlan.Input> inputs(int count, MeshBuild<?> mesh) {
        var values = new ArrayList<RtInstanceTablePlan.Input>();
        for (int index = 0; index < count; index++) {
            values.add(new RtInstanceTablePlan.Input(index + 1, index + 1, mesh,
                    GeometryTransform.translation(30_000_000.5 + index, 3, 4), index));
        }
        return List.copyOf(values);
    }

    private static MeshBuild<?> mesh() {
        var data = ShaderDataType.<Object>create("packed-instance-page");
        var surface = new SurfaceId<Object, Object>() { };
        var geometry = new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(surface, data.data(0),
                new MeshBuild.CoveragePolicy.Opaque()), null, 0, 3);
        return new MeshBuild<>(stream(0x1000, 36, 12), stream(0x2000, 12, 4), 3,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, List.of(geometry));
    }

    private static MeshBuild.Stream stream(long address, int size, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), size),
                stride, ResourceOwner.none());
    }
}
