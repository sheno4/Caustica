package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedGeometryPlanTest {
    interface Binding { }
    interface Instance { }
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final SurfaceId<Binding, Instance> SURFACE = new SurfaceId<>() { };
    private static final VolumeId<Binding, Instance> VOLUME = new VolumeId<>() { };

    @Test
    void blasRangesPreserveSliceOrderOffsetsAndCoverageClass() {
        MeshBuild<Instance> build = build(new MeshBuild.IndexRevision(7), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22),
                        new MeshBuild.CoveragePolicy.Cutout(0.4f, null)));

        var ranges = RtRetainedGeometryPlan.blasRanges(build);

        assertEquals(List.of(
                new dev.comfyfluffy.caustica.rt.accel.RtAccel.GeometryRange(3, 6, true),
                new dev.comfyfluffy.caustica.rt.accel.RtAccel.GeometryRange(12, 9, false)), ranges);
    }

    @Test
    void reuseRequiresStableInputsRevisionAndGeometryShapeButNotBindingWords() {
        MeshBuild<Instance> first = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(2),
                        new MeshBuild.CoveragePolicy.Cutout(0.25f, null)));
        MeshBuild<Instance> bindingOnly = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f, null)));
        MeshBuild<Instance> movedPositions = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f, null)));

        assertTrue(RtRetainedGeometryPlan.canReuseBlas(first, bindingOnly));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first, movedPositions));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first,
                build(null, 0x1000,
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10),
                                new MeshBuild.CoveragePolicy.Opaque()),
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                                new MeshBuild.CoveragePolicy.Cutout(0.75f, null)))));
    }

    @Test
    void geometryAbiPacksProgramsRootsAndRebasedTransformHistory() {
        GeometryTransform current = GeometryTransform.translation(110, 220, 330);
        GeometryTransform previous = GeometryTransform.translation(109, 218, 327);
        var first = new RtRetainedGeometryPlan.GeometryRecord(3, 3, 5,
                RtRetainedGeometryPlan.HAS_SURFACE | RtRetainedGeometryPlan.HAS_VOLUME
                        | RtRetainedGeometryPlan.CUTOUT,
                0x1111, 0x2222, 0x3333, 0.45f, current, previous);
        var second = new RtRetainedGeometryPlan.GeometryRecord(7, 0, 0,
                RtRetainedGeometryPlan.HAS_SURFACE, 0x4444, 0, 0x5555, 0,
                current, current);

        ByteBuffer packed = RtRetainedGeometryPlan.pack(List.of(first, second),
                new SceneOrigin(100, 200, 300));

        assertEquals(2 * RtRetainedGeometryPlan.RECORD_BYTES, packed.remaining());
        assertEquals(3, packed.getInt(RtRetainedGeometryPlan.SURFACE_IMPLEMENTATION_OFFSET));
        assertEquals(3, packed.getInt(RtRetainedGeometryPlan.COVERAGE_IMPLEMENTATION_OFFSET));
        assertEquals(5, packed.getInt(RtRetainedGeometryPlan.VOLUME_IMPLEMENTATION_OFFSET));
        assertEquals(0x1111, packed.getLong(RtRetainedGeometryPlan.SURFACE_BINDING_OFFSET));
        assertEquals(0x2222, packed.getLong(RtRetainedGeometryPlan.VOLUME_BINDING_OFFSET));
        assertEquals(0x3333, packed.getLong(RtRetainedGeometryPlan.INSTANCE_DATA_OFFSET));
        assertEquals(10.0f, packed.getFloat(RtRetainedGeometryPlan.CURRENT_TRANSFORM_OFFSET + 3 * 4));
        assertEquals(9.0f, packed.getFloat(RtRetainedGeometryPlan.PREVIOUS_TRANSFORM_OFFSET + 3 * 4));
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_CUTOUT,
                        RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_OPAQUE),
                RtRetainedGeometryPlan.hitGroups(List.of(first, second)));
    }

    @Test
    void volumeBoundaryStaysOpaqueWhileRetainingVisibleSurfaceCutoutData() {
        MeshBuild.Stream positions = stream(0x1000, 256, 12);
        MeshBuild.Stream indices = stream(0x2000, 64, 4);
        MeshBuild<Instance> build = new MeshBuild<>(positions, null, indices, 8,
                new MeshBuild.IndexRevision(1), List.of(new MeshBuild.Geometry<>(
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1),
                        new MeshBuild.CoveragePolicy.Cutout(0.5f, null)),
                new MeshBuild.VolumeSlot<>(VOLUME, BINDING.data(2)), 0, 6)));
        var range = RtRetainedGeometryPlan.blasRanges(build).getFirst();
        var record = new RtRetainedGeometryPlan.GeometryRecord(1, 1, 2,
                RtRetainedGeometryPlan.HAS_SURFACE | RtRetainedGeometryPlan.HAS_VOLUME
                        | RtRetainedGeometryPlan.CUTOUT,
                1, 2, 3, 0.5f, GeometryTransform.translation(0, 0, 0),
                GeometryTransform.translation(0, 0, 0));

        assertTrue(range.opaque());
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_OPAQUE),
                RtRetainedGeometryPlan.hitGroups(List.of(record)));
        assertTrue((record.flags() & RtRetainedGeometryPlan.CUTOUT) != 0);
    }

    private static MeshBuild<Instance> build(MeshBuild.IndexRevision revision, long positionAddress,
                                             MeshBuild.SurfaceSlot<Binding, Instance> first,
                                             MeshBuild.SurfaceSlot<Binding, Instance> second) {
        return new MeshBuild<>(stream(positionAddress, 256, 12), null,
                stream(0x2000, 128, 4), 8, revision, List.of(
                new MeshBuild.Geometry<>(first, null, 3, 6),
                new MeshBuild.Geometry<>(second, null, 12, 9)));
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes), stride);
    }
}
