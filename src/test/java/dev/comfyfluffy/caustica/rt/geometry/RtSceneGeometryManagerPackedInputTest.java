package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSceneGeometryManagerPackedInputTest {
    @Test
    void acceptsCompletePackedTriangle() {
        assertDoesNotThrow(() -> input(new int[]{0, 1, 2}, new int[]{1, 0, 0}));
    }

    @Test
    void rejectsClassCountsThatDoNotCoverTheIndexTriangles() {
        assertThrows(IllegalArgumentException.class, () -> input(new int[]{0, 1, 2}, new int[]{0, 0, 0}));
    }

    @Test
    void rejectsIndexOutsideVertexRange() {
        assertThrows(IllegalArgumentException.class, () -> input(new int[]{0, 1, 3}, new int[]{1, 0, 0}));
    }

    @Test
    void rejectsMissingPrimitiveRecords() {
        assertThrows(IllegalArgumentException.class, () -> new RtSceneGeometryManager.PackedInput(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2}, new float[6], new float[0],
                new int[]{1, 0, 0}, 0, 7L, RtSceneGeometryManager.BuildClass.DEFORMING, 0));
    }

    @Test
    void vertexCountIsPartOfDeformingRefitTopology() {
        assertTrue(RtSceneGeometryManager.deformingTopologyMatches(7L, 3, 7L, 3));
        assertFalse(RtSceneGeometryManager.deformingTopologyMatches(7L, 3, 7L, 4));
    }

    @Test
    void topologyMismatchResetsIndexedMotionHistory() {
        assertTrue(RtSceneGeometryManager.indexedMotionCompatible(
                RtSceneGeometryManager.BuildClass.DEFORMING, true, 7L, 3, 7L, 3));
        assertTrue(RtSceneGeometryManager.indexedMotionCompatible(
                RtSceneGeometryManager.BuildClass.STATIC, true, 7L, 3, 7L, 3));
        assertFalse(RtSceneGeometryManager.indexedMotionCompatible(
                RtSceneGeometryManager.BuildClass.DEFORMING, true, 7L, 3, 8L, 3));
        assertFalse(RtSceneGeometryManager.indexedMotionCompatible(
                RtSceneGeometryManager.BuildClass.DEFORMING, true, 7L, 3, 7L, 4));
        assertFalse(RtSceneGeometryManager.indexedMotionCompatible(
                RtSceneGeometryManager.BuildClass.REBUILT, true, 7L, 3, 7L, 3));
        assertFalse(RtSceneGeometryManager.indexedMotionCompatible(
                RtSceneGeometryManager.BuildClass.STATIC, false, 7L, 3, 7L, 3));
    }

    private static RtSceneGeometryManager.PackedInput input(int[] indices, int[] classes) {
        return new RtSceneGeometryManager.PackedInput(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, indices, new float[6], new float[12],
                classes, 0, 7L, RtSceneGeometryManager.BuildClass.DEFORMING, 0);
    }
}
