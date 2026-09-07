package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.BitSet;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTracePageResidencyTest {
    @Test void slotRetainsReorderedGenerationsAndDiscardsAnEqualNumericReplacement() {
        var slot = new RtRetainedSceneBackend.TraceSlot(null, null, null, null, null);
        var first = new RtStableTraceRanges.PageRange(0, 2, 0, 8);
        var second = new RtStableTraceRanges.PageRange(2, 1, 8, 4);
        var initial = new IdentityHashMap<RtStableTraceRanges.PageRange, RtRetainedSceneBackend.TracePageResidency>();
        initial.put(first, slot.page(first));
        initial.put(second, slot.page(second));
        slot.retainPages(initial);
        var reordered = new IdentityHashMap<RtStableTraceRanges.PageRange, RtRetainedSceneBackend.TracePageResidency>();
        reordered.put(second, slot.page(second));
        reordered.put(first, slot.page(first));
        slot.retainPages(reordered);
        org.junit.jupiter.api.Assertions.assertSame(initial.get(first), slot.page(first));
        org.junit.jupiter.api.Assertions.assertSame(initial.get(second), slot.page(second));

        var replacement = new RtStableTraceRanges.PageRange(0, 2, 0, 8);
        var updated = new IdentityHashMap<RtStableTraceRanges.PageRange, RtRetainedSceneBackend.TracePageResidency>();
        updated.put(second, slot.page(second));
        updated.put(replacement, slot.page(replacement));
        org.junit.jupiter.api.Assertions.assertNotSame(initial.get(first), updated.get(replacement));
        slot.retainPages(updated);
        assertEquals(2, slot.pages.size());
        assertFalse(slot.pages.containsKey(first));
        assertTrue(slot.pages.containsKey(replacement));
        org.junit.jupiter.api.Assertions.assertSame(initial.get(second), slot.page(second));
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
    void lightIndexRevisionInvalidatesOnlyEmitterIndices() {
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
        var firstIndices = new IntOpenHashSet();
        firstIndices.add(2);
        firstIndices.add(1_000_000);
        firstIndices.add(1_000_000);
        first.linkedEmittersWritten(firstIndices);
        second.linkedEmittersWritten(new IntOpenHashSet(new int[]{1_000_000, 500_000}));
        firstIndices.clear();

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
        residency.linkedEmittersWritten(new IntOpenHashSet(new int[]{1, 1_000_000}));
        residency.linkedEmittersWritten(new IntOpenHashSet(new int[]{7}));
        var scene = new BitSet();
        residency.addLinkedEmittersTo(scene);
        assertEquals(1, scene.cardinality());
        assertTrue(scene.get(7));
        assertFalse(scene.get(1));
        assertFalse(scene.get(1_000_000));

        residency.linkedEmittersWritten(new IntOpenHashSet());
        scene.clear();
        residency.addLinkedEmittersTo(scene);
        assertTrue(scene.isEmpty());
    }

}
