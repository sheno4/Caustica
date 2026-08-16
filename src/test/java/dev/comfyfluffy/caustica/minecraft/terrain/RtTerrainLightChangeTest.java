package dev.comfyfluffy.caustica.minecraft.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainLightChangeTest {
    @Test
    void treatsMissingAndEmptyLightListsAsEquivalent() {
        assertTrue(RtTerrain.sameLightRecords(null, null));
        assertTrue(RtTerrain.sameLightRecords(null, new float[0]));
        assertTrue(RtTerrain.sameLightRecords(new float[0], null));
    }

    @Test
    void acceptsBitIdenticalReextractionAndRejectsActualChanges() {
        float[] original = {1f, 2f, 3f, 4f};
        assertTrue(RtTerrain.sameLightRecords(original, original.clone()));
        assertFalse(RtTerrain.sameLightRecords(original, new float[]{1f, 2f, 3f, 5f}));
        assertFalse(RtTerrain.sameLightRecords(original, null));
        assertFalse(RtTerrain.sameLightRecords(null, original));
    }

    @Test
    void pendingPublicationRemainsEligibleForDirtyRebuildAndEviction() {
        assertTrue(RtTerrain.canRebuildSection(false, true, false));
        assertTrue(RtTerrain.canRebuildSection(true, false, false));
        assertTrue(RtTerrain.canRebuildSection(false, false, true));
        assertFalse(RtTerrain.canRebuildSection(false, false, false));
        assertTrue(RtTerrain.requiresDrop(false, true));
        assertTrue(RtTerrain.requiresDrop(true, false));
        assertFalse(RtTerrain.requiresDrop(false, false));
    }

    @Test
    void cancelledDirtyMemberNeverBecomesSingletonPublication() {
        assertTrue(RtTerrain.discardsCancelledDirtyGroup(1L, false));
        assertFalse(RtTerrain.discardsCancelledDirtyGroup(1L, true));
        assertFalse(RtTerrain.discardsCancelledDirtyGroup(0L, false));
    }

    @Test
    void frameDrainKeepsOnlyTheLatestSubmissionForOneGroupKey() {
        var first = new dev.comfyfluffy.caustica.api.provider.SceneGeometryKey(10, 7);
        var other = new dev.comfyfluffy.caustica.api.provider.SceneGeometryKey(10, 8);
        assertEquals(java.util.List.of(first, other), RtTerrain.latestGroupKeys(java.util.List.of(first, other, first)));
    }

    @Test
    void lateDrainBudgetIsBoundedOneShotAndDoesNotCarryAcrossReset() {
        RtTerrain.LateCompletionBudget budget = new RtTerrain.LateCompletionBudget();

        budget.arm(8, 3);
        assertEquals(5, budget.consume());
        assertEquals(0, budget.consume());

        budget.arm(8, 2);
        budget.reset();
        assertEquals(0, budget.consume());

        budget.arm(8, 8);
        assertEquals(0, budget.consume());
    }

    @Test
    void emptyStateIsAnAcknowledgedDropTransition() {
        assertTrue(RtTerrain.emptyAfterDrop(true));
        assertFalse(RtTerrain.emptyAfterDrop(false));
    }

    @Test
    void retainedLightMarksBothSourceTrianglesAndLeavesOthersUnmarked() {
        var material = dev.comfyfluffy.caustica.api.provider.MaterialHandle.of("test", "emitter");
        var surface = dev.comfyfluffy.caustica.api.provider.SceneMesh.TriangleSurface.surface(material);
        var surfaces = new java.util.ArrayList<>(java.util.List.of(surface, surface, surface, surface));

        RtLightCollector.markEmitterInLightScene(surfaces, 0);

        assertTrue(surfaces.get(0).emitterInLightScene());
        assertTrue(surfaces.get(1).emitterInLightScene());
        assertFalse(surfaces.get(2).emitterInLightScene());
        assertFalse(surfaces.get(3).emitterInLightScene());
    }
}
