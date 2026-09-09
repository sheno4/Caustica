package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtTracePageResidencyTest {
    @Test void instanceMembershipChangesPreserveIndependentHitAndEmitterResidency() {
        var slot = new RtRetainedSceneBackend.TraceSlot(null, null, null, null, null);
        var builder = new RtInstanceTablePlan.Builder();
        slot.setInstanceTable(builder.build(java.util.List.of()));
        var ranges = new RtStableTraceRanges();
        var range = ranges.reserve(1, 4);
        var batch = new RtRetainedSceneBackend.TraceBatch(new RtRetainedSceneBackend.TracePagePlan[0]);
        var batchResidency = slot.batch(batch, batch);
        var page = slot.page(range, batchResidency);
        Object identity = new Object(), pipeline = new Object(), lights = new Object();
        batchResidency.written(pipeline, lights, slot.instanceAssignments);
        page.geometryWritten(identity, 0, 0x1000, 23);
        page.hitsWritten(identity, 0, pipeline);
        page.emittersWritten(range, 0, lights);
        var positions = new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x1000), 36),
                12, ResourceOwner.none());
        var indices = new MeshBuild.Stream(positions.bytes(), 4, ResourceOwner.none());
        var surface = new MeshBuild.SurfaceSlot<>(new SurfaceId<Object, Object>() {},
                ShaderDataType.<Object>create("residency").data(0), new MeshBuild.CoveragePolicy.Opaque());
        var mesh = new MeshBuild<>(positions, indices, 3, null, MeshBuild.BuildPolicy.REFITTABLE,
                java.util.List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        slot.setInstanceTable(builder.build(java.util.List.of(new RtInstanceTablePlan.Input(1, 0, mesh,
                GeometryTransform.translation(0, 0, 0), 0))));
        assertSame(batchResidency, slot.batch(batch, batch));
        assertSame(page, slot.page(range, batchResidency));
        assertFalse(batchResidency.hasGeometry(pipeline, slot.instanceAssignments));
        assertTrue(batchResidency.hasEmitters(lights));
        assertTrue(page.hasGeometry(identity, 0, 0x1000, 23));
        assertFalse(page.hasGeometry(identity, 0, 0x1000, 24));
        assertTrue(page.hasHits(identity, 0, pipeline));
        assertTrue(page.hasEmitters(range, 0, lights));
    }

    @Test void batchValidationSeparatesGeometryAndEmitterRevisions() {
        var batch = new RtRetainedSceneBackend.TraceBatch(new RtRetainedSceneBackend.TracePagePlan[0]);
        var residency = new RtRetainedSceneBackend.TraceBatchResidency(batch);
        Object pipeline = new Object(), lights = new Object();
        assertFalse(residency.hasGeometry(pipeline, 1));
        assertFalse(residency.hasEmitters(lights));
        residency.written(pipeline, lights, 1);
        assertTrue(residency.hasGeometry(pipeline, 1));
        assertTrue(residency.hasEmitters(lights));
        assertFalse(residency.hasGeometry(new Object(), 1));
        assertFalse(residency.hasGeometry(pipeline, 2));
        assertFalse(residency.hasEmitters(new Object()));
        var nextSlot = new RtRetainedSceneBackend.TraceBatchResidency(batch);
        assertFalse(nextSlot.hasGeometry(pipeline, 1));
        assertFalse(nextSlot.hasEmitters(lights));
    }

    @Test
    void cameraOnlyFramesKeepEveryPackedRegionResident() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object page = new Object();
        Object pipeline = new Object();
        Object lightIndices = new Object();

        residency.geometryWritten(page, 17, 0x4000, 23);
        residency.hitsWritten(page, 34, pipeline);
        residency.emittersWritten(page, 96, lightIndices);

        assertTrue(residency.hasGeometry(page, 17, 0x4000, 23));
        assertTrue(residency.hasHits(page, 34, pipeline));
        assertTrue(residency.hasEmitters(page, 96, lightIndices));
    }

    @Test
    void emitterGenerationInvalidatesOnlyEmitterIndices() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object page = new Object();
        Object pipeline = new Object();
        Object previousLightIndices = new Object();
        Object currentLightIndices = new Object();
        residency.geometryWritten(page, 0, 0x8000, 23);
        residency.hitsWritten(page, 0, pipeline);
        residency.emittersWritten(page, 0, previousLightIndices);

        assertTrue(residency.hasGeometry(page, 0, 0x8000, 23));
        assertTrue(residency.hasHits(page, 0, pipeline));
        assertFalse(residency.hasEmitters(page, 0, currentLightIndices));
    }

    @Test
    void geometryTracksPageAbsoluteOffsetAndEmitterAddress() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object page = new Object();
        residency.geometryWritten(page, 12, 0x1000, 23);

        assertFalse(residency.hasGeometry(new Object(), 12, 0x1000, 23));
        assertFalse(residency.hasGeometry(page, 13, 0x1000, 23));
        assertFalse(residency.hasGeometry(page, 12, 0x2000, 23));
    }

    @Test
    void hitTableTracksPageOffsetAndPipelineIndependently() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object page = new Object();
        Object pipeline = new Object();
        residency.hitsWritten(page, 64, pipeline);

        assertFalse(residency.hasHits(new Object(), 64, pipeline));
        assertFalse(residency.hasHits(page, 128, pipeline));
        assertFalse(residency.hasHits(page, 64, new Object()));
    }
    @Test
    void sparseHighIndicesMergeAcrossPagesWithoutStoringTheirUnusedRange() {
        var first = new RtRetainedSceneBackend.TracePageResidency();
        var second = new RtRetainedSceneBackend.TracePageResidency();
        first.linkedEmittersWritten(new int[]{2, 1_000_000});
        second.linkedEmittersWritten(new int[]{1_000_000, 500_000});

        assertEquals(2, first.linkedEmitters.length);
        assertEquals(2, second.linkedEmitters.length);
        var scene = new BitSet();
        first.addLinkedEmittersTo(scene);
        second.addLinkedEmittersTo(scene);
        assertEquals(3, scene.cardinality());
        assertTrue(scene.get(2));
        assertTrue(scene.get(500_000));
        assertTrue(scene.get(1_000_000));
    }

    @Test
    void replacedPageLinksDoNotKeepRemovedLightsInTheSceneUnion() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        residency.linkedEmittersWritten(new int[]{1, 1_000_000});
        residency.linkedEmittersWritten(new int[]{7});
        var scene = new BitSet();
        residency.addLinkedEmittersTo(scene);
        assertEquals(1, scene.cardinality());
        assertTrue(scene.get(7));
        assertFalse(scene.get(1));
        assertFalse(scene.get(1_000_000));

        residency.linkedEmittersWritten(new int[0]);
        scene.clear();
        residency.addLinkedEmittersTo(scene);
        assertTrue(scene.isEmpty());
    }

}
