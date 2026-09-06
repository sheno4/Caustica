package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
                        new MeshBuild.CoveragePolicy.Cutout(0.4f)));

        var ranges = RtRetainedGeometryPlan.blasRanges(build);

        assertEquals(List.of(
                new RtAccel.GeometryRange(3, 6, true),
                new RtAccel.GeometryRange(12, 9, false)), ranges);
    }

    @Test
    void stochasticCoverageUsesAnyHitAndPreservesItsGuideCutoff() {
        MeshBuild<Instance> build = build(new MeshBuild.IndexRevision(7), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11),
                        new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22),
                        new MeshBuild.CoveragePolicy.Stochastic(0.35f)));
        MeshBuild.Stream renderedPredecessor = stream(0xa000, 256, 24);
        var mesh = new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.Mesh(
                1, build, List.of(
                new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.GeometryPrograms(1, 0),
                new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.GeometryPrograms(1, 0)), null);
        var instance = new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.Instance(
                1, 1, new dev.comfyfluffy.caustica.api.scene.SceneId() { }, 1,
                GeometryTransform.translation(0, 0, 0), 0xff, BINDING.data(0), List.of());

        var record = RtRetainedGeometryPlan.records(mesh, instance,
                GeometryTransform.translation(0, 0, 0), renderedPredecessor).get(1);

        assertFalse(RtRetainedGeometryPlan.blasRanges(build).get(1).opaque());
        assertTrue((record.flags() & RtRetainedGeometryPlan.STOCHASTIC) != 0);
        assertEquals(0.35f, record.alphaCutoff());
        assertEquals(renderedPredecessor.bytes().address(), record.previousPositionAddress());
        assertEquals(24, record.previousPositionStride());
        assertEquals(build.indices().bytes().address(), record.indexAddress());
        assertEquals(12, record.firstIndex());
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_CUTOUT),
                RtRetainedGeometryPlan.hitGroups(List.of(record)));
    }

    @Test
    void ordinaryRecordsUseCurrentPositions() {
        MeshBuild<Instance> build = build(new MeshBuild.IndexRevision(7), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11),
                        new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22),
                        new MeshBuild.CoveragePolicy.Opaque()));
        var mesh = new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.Mesh(
                1, build, List.of(
                new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.GeometryPrograms(1, 0),
                new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.GeometryPrograms(1, 0)), null);
        var instance = new dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.Instance(
                1, 1, new dev.comfyfluffy.caustica.api.scene.SceneId() { }, 1,
                GeometryTransform.translation(0, 0, 0), 0xff, BINDING.data(0), List.of());

        var record = RtRetainedGeometryPlan.records(mesh, instance,
                GeometryTransform.translation(0, 0, 0)).getFirst();

        assertEquals(build.positions().bytes().address(), record.previousPositionAddress());
        assertEquals(build.positions().byteStride(), record.previousPositionStride());
    }

    @Test
    void reuseRequiresStableInputsRevisionAndGeometryShapeButNotBindingWords() {
        MeshBuild<Instance> first = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(2),
                        new MeshBuild.CoveragePolicy.Cutout(0.25f)));
        MeshBuild<Instance> bindingOnly = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f)));
        MeshBuild<Instance> movedPositions = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f)));

        assertTrue(RtRetainedGeometryPlan.canReuseBlas(first, bindingOnly));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first, movedPositions));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first,
                build(null, 0x1000,
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10),
                                new MeshBuild.CoveragePolicy.Opaque()),
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                                new MeshBuild.CoveragePolicy.Cutout(0.75f)))));
    }

    @Test
    void refitRequiresChangedPositionsAndAnIdenticalUpdateLayout() {
        MeshBuild<Instance> first = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(2),
                        new MeshBuild.CoveragePolicy.Cutout(0.25f)));
        MeshBuild<Instance> moved = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f)));
        MeshBuild<Instance> changedTraversal = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Opaque()));

        assertTrue(RtRetainedGeometryPlan.canRefitBlas(first, moved));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first, first));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first, changedTraversal));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first,
                build(new MeshBuild.IndexRevision(10), 0x3000,
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10),
                                new MeshBuild.CoveragePolicy.Opaque()),
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                                new MeshBuild.CoveragePolicy.Cutout(0.75f)))));
    }

    @Test
    void staticBlasCanBeSharedButCannotBeRefittedOrReusedAcrossPolicies() {
        var slot = new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1),
                new MeshBuild.CoveragePolicy.Opaque());
        var refittable = build(new MeshBuild.IndexRevision(9), 0x1000, slot, slot);
        var moved = build(new MeshBuild.IndexRevision(9), 0x3000, slot, slot);
        var fixed = withPolicy(refittable, MeshBuild.BuildPolicy.STATIC);
        var fixedMoved = withPolicy(moved, MeshBuild.BuildPolicy.STATIC);

        assertTrue(RtRetainedGeometryPlan.canReuseBlas(fixed, fixed));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(fixed, refittable));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(refittable, fixed));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(fixed, fixedMoved));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(fixed, moved));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(refittable, fixedMoved));
        assertTrue(RtRetainedGeometryPlan.canRefitBlas(refittable, moved));
    }

    private static MeshBuild<Instance> withPolicy(MeshBuild<Instance> build, MeshBuild.BuildPolicy policy) {
        return new MeshBuild<>(build.positions(), build.indices(), build.vertexCount(),
                build.indexRevision(), policy, build.geometries());
    }

    @Test
    void geometryAbiPacksProgramsRootsAndRebasedTransformHistory() {
        GeometryTransform current = GeometryTransform.translation(110, 220, 330);
        GeometryTransform previous = GeometryTransform.translation(109, 218, 327);
        var first = new RtRetainedGeometryPlan.GeometryRecord(3, 3, 5,
                RtRetainedGeometryPlan.HAS_SURFACE | RtRetainedGeometryPlan.HAS_VOLUME
                        | RtRetainedGeometryPlan.CUTOUT,
                0x1111, 0x2222, 0x3333, 0.45f, current, previous,
                new VulkanDeviceAddress(0x5550), 20, new VulkanDeviceAddress(0x6660), 3,
                new VulkanDeviceAddress(0x4444), 6);
        var second = new RtRetainedGeometryPlan.GeometryRecord(7, 0, 0,
                RtRetainedGeometryPlan.HAS_SURFACE, 0x4444, 0, 0x5555, 0,
                current, current, new VulkanDeviceAddress(0x7770), 12,
                new VulkanDeviceAddress(0x8880), 12, null, 0);

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
        assertEquals(0x4444, packed.getLong(RtRetainedGeometryPlan.EMITTER_INDEX_ADDRESS_OFFSET));
        assertEquals(6, packed.getInt(RtRetainedGeometryPlan.EMITTER_PRIMITIVE_BASE_OFFSET));
        assertEquals(20, packed.getInt(RtRetainedGeometryPlan.PREVIOUS_POSITION_STRIDE_OFFSET));
        assertEquals(0x5550, packed.getLong(RtRetainedGeometryPlan.PREVIOUS_POSITION_ADDRESS_OFFSET));
        assertEquals(0x6660, packed.getLong(RtRetainedGeometryPlan.INDEX_ADDRESS_OFFSET));
        assertEquals(3, packed.getInt(RtRetainedGeometryPlan.FIRST_INDEX_OFFSET));
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_TRANSMISSIVE,
                        RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_OPAQUE),
                RtRetainedGeometryPlan.hitGroups(List.of(first, second)));
    }

    @Test
    void volumeBoundaryUsesOpaqueRadianceAndTransmissiveShadowRouting() {
        MeshBuild.Stream positions = stream(0x1000, 256, 12);
        MeshBuild.Stream indices = stream(0x2000, 64, 4);
        MeshBuild<Instance> build = new MeshBuild<>(positions, indices, 8,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.REFITTABLE, List.of(new MeshBuild.Geometry<>(
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1),
                        new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                new MeshBuild.VolumeSlot<>(VOLUME, BINDING.data(2)), 0, 6)));
        var range = RtRetainedGeometryPlan.blasRanges(build).getFirst();
        var record = new RtRetainedGeometryPlan.GeometryRecord(1, 1, 2,
                RtRetainedGeometryPlan.HAS_SURFACE | RtRetainedGeometryPlan.HAS_VOLUME
                        | RtRetainedGeometryPlan.CUTOUT,
                1, 2, 3, 0.5f, GeometryTransform.translation(0, 0, 0),
                GeometryTransform.translation(0, 0, 0), positions.bytes().address(),
                positions.byteStride(), indices.bytes().address(), 0, null, 0);

        assertTrue(range.opaque());
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                        RtRetainedGeometryPlan.HitGroup.SHADOW_TRANSMISSIVE),
                RtRetainedGeometryPlan.hitGroups(List.of(record)));
        assertTrue((record.flags() & RtRetainedGeometryPlan.CUTOUT) != 0);
    }

    @Test
    void preparedTraceWritesOnlyItsOwnedWorldRoots() {
        ByteBuffer roots = ByteBuffer.allocate(RtBindings.WORLD_PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        roots.putLong(RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET, 0x7777L);
        var trace = new RtRetainedSceneBackend.PreparedTrace(
                new VulkanDeviceAddress(0x1234L), new VulkanDeviceAddress(0x5678L), 19,
                new RtPipeline.HitTable(new VulkanDeviceAddressRange(
                        new VulkanDeviceAddress(0x8000L), 256), 64));

        trace.writeWorldRoots(roots);

        assertEquals(0x1234L, roots.getLong(RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET));
        assertEquals(19, roots.getInt(RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET));
        assertEquals(0x5678L, roots.getLong(RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET));
        assertEquals(0x7777L, roots.getLong(RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET));
    }

    private static MeshBuild<Instance> build(MeshBuild.IndexRevision revision, long positionAddress,
                                             MeshBuild.SurfaceSlot<Binding, Instance> first,
                                             MeshBuild.SurfaceSlot<Binding, Instance> second) {
        return new MeshBuild<>(stream(positionAddress, 256, 12),
                stream(0x2000, 128, 4), 8, revision, MeshBuild.BuildPolicy.REFITTABLE, List.of(
                new MeshBuild.Geometry<>(first, null, 3, 6),
                new MeshBuild.Geometry<>(second, null, 12, 9)));
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes), stride,
                ResourceOwner.none());
    }
}
