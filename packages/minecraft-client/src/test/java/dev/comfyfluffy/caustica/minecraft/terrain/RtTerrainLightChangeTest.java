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
        long first = 7L;
        long other = 8L;
        assertEquals(java.util.List.of(first, other), RtTerrain.latestGroupKeys(java.util.List.of(first, other, first)));
    }

    @Test
    void emptyStateIsAnAcknowledgedDropTransition() {
        assertTrue(RtTerrain.emptyAfterDrop(true));
        assertFalse(RtTerrain.emptyAfterDrop(false));
    }

}
