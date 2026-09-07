package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTracePageResidencyTest {
    @Test void batchValidationSeparatesGeometryAndEmitterRevisions() {
        var batch = new RtRetainedSceneBackend.TraceBatch(new RtRetainedSceneBackend.TracePagePlan[0]);
        var residency = new RtRetainedSceneBackend.TraceBatchResidency(batch);
        var origin = new SceneOrigin(0, 0, 0);
        Object pipeline = new Object(), lights = new Object();
        assertFalse(residency.hasGeometry(origin, pipeline));
        assertFalse(residency.hasEmitters(lights));
        residency.written(origin, pipeline, lights);
        assertTrue(residency.hasGeometry(new SceneOrigin(0, 0, 0), pipeline));
        assertTrue(residency.hasEmitters(lights));
        assertFalse(residency.hasGeometry(new SceneOrigin(1, 0, 0), pipeline));
        assertFalse(residency.hasGeometry(origin, new Object()));
        assertFalse(residency.hasEmitters(new Object()));
        var nextSlot = new RtRetainedSceneBackend.TraceBatchResidency(batch);
        assertFalse(nextSlot.hasGeometry(origin, pipeline));
        assertFalse(nextSlot.hasEmitters(lights));
    }

    @Test
    void cameraOnlyFramesKeepEveryPackedRegionResident() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object page = new Object();
        Object pipeline = new Object();
        Object lightIndices = new Object();
        var origin = new SceneOrigin(128, -64, 256);

        residency.geometryWritten(page, 17, origin, 0x4000);
        residency.hitsWritten(page, 34, pipeline);
        residency.emittersWritten(page, 96, lightIndices);

        assertTrue(residency.hasGeometry(page, 17, origin, 0x4000));
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
        var origin = SceneOrigin.ZERO;
        residency.geometryWritten(page, 0, origin, 0x8000);
        residency.hitsWritten(page, 0, pipeline);
        residency.emittersWritten(page, 0, previousLightIndices);

        assertTrue(residency.hasGeometry(page, 0, origin, 0x8000));
        assertTrue(residency.hasHits(page, 0, pipeline));
        assertFalse(residency.hasEmitters(page, 0, currentLightIndices));
    }

    @Test
    void geometryTracksOriginAbsoluteOffsetAndEmitterAddress() {
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object page = new Object();
        var origin = new SceneOrigin(128, 0, 128);
        residency.geometryWritten(page, 12, origin, 0x1000);

        assertFalse(residency.hasGeometry(new Object(), 12, origin, 0x1000));
        assertFalse(residency.hasGeometry(page, 13, origin, 0x1000));
        assertFalse(residency.hasGeometry(page, 12, new SceneOrigin(256, 0, 128), 0x1000));
        assertFalse(residency.hasGeometry(page, 12, origin, 0x2000));
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
