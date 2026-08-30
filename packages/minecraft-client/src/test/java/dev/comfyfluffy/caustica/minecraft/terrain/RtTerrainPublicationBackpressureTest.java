package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainPublicationBackpressureTest {
    @Test
    void pendingReceiptKeepsTheSubmittedGroupInFlight() {
        boolean[] visible = {false};
        GeometryPublication publication = () -> visible[0];

        assertTrue(RtTerrain.publicationPending(publication));
        visible[0] = true;
        assertFalse(RtTerrain.publicationPending(publication));
    }

    @Test
    void admissionSelectsAnOldestFirstPrefixWithinTheChangeBudget() {
        assertEquals(2, RtTerrain.boundedAdmissionGroupCount(java.util.List.of(3, 4, 5), 8));
    }

    @Test
    void admissionNeverSplitsAnOversizedAtomicDirtyGroup() {
        assertEquals(1, RtTerrain.boundedAdmissionGroupCount(java.util.List.of(12, 1), 8));
    }

    @Test
    void newlyBuiltEmptyMissingSectionNeedsNoNativePublication() {
        assertFalse(RtTerrain.emptyBuildRequiresPublication(false, false, false));
        assertTrue(RtTerrain.emptyBuildRequiresPublication(false, true, false));
        assertTrue(RtTerrain.emptyBuildRequiresPublication(false, false, true));
    }

    @Test
    void acceptedGeometryStillRequiresDropAfterLocalClearStateWasReset() {
        assertTrue(RtTerrain.emptyBuildRequiresPublication(true, false, false));
    }

    @Test
    void fullClearAndNoWorldWaitForAnInvisibleAcceptedPublication() {
        boolean[] visible = {false};
        GeometryPublication publication = () -> visible[0];

        assertTrue(RtTerrain.clearMustWait(publication));
        visible[0] = true;
        assertFalse(RtTerrain.clearMustWait(publication));
    }

    @Test
    void sectionAndDirtyGroupKeysCannotCollide() {
        var section = new RtTerrain.GeometryGroupKey(RtTerrain.GeometryGroupKind.SECTION, 1L);
        var dirty = new RtTerrain.GeometryGroupKey(RtTerrain.GeometryGroupKind.DIRTY, 1L);

        assertFalse(section.equals(dirty));
    }

    @Test
    void staleEpochTelemetryCountsEachSectionOnce() {
        assertEquals(3, RtTerrain.uniqueRejectedSectionCount(
                java.util.List.of(1L), java.util.List.of(2L, 3L), java.util.List.of(2L)));
    }

    @Test
    void largeEvictionBacklogHasBoundedPerPassConversion() {
        assertEquals(8, RtTerrain.boundedEvictionCount(14_000, 8));
        assertEquals(3, RtTerrain.boundedEvictionCount(3, 8));
    }

    @Test
    void emptyDirtyGroupWaitsWhenAnyMemberStillRequiresNativeOrdering() {
        assertTrue(RtTerrain.emptyDirtyGroupCanCompleteImmediately(false, false));
        assertFalse(RtTerrain.emptyDirtyGroupCanCompleteImmediately(true, false));
        assertFalse(RtTerrain.emptyDirtyGroupCanCompleteImmediately(false, true));
    }
}
