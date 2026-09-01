package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.api.retained.RetainedPublication;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainPublicationBackpressureTest {
    @Test
    void pendingReceiptKeepsTheSubmittedGroupInFlight() {
        MutablePublication publication = new MutablePublication();

        assertTrue(RtTerrain.publicationPending(publication));
        publication.makeVisible();
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
        MutablePublication publication = new MutablePublication();

        assertTrue(RtTerrain.clearMustWait(publication));
        publication.makeVisible();
        assertFalse(RtTerrain.clearMustWait(publication));
    }

    @Test
    void sectionAndDirtyGroupKeysCannotCollide() {
        var section = new RtTerrain.GeometryGroupKey(RtTerrain.GeometryGroupKind.SECTION, 1L);
        var dirty = new RtTerrain.GeometryGroupKey(RtTerrain.GeometryGroupKind.DIRTY, 1L);
        var teardown = new RtTerrain.GeometryGroupKey(RtTerrain.GeometryGroupKind.TEARDOWN, 1L);

        assertFalse(section.equals(dirty));
        assertFalse(section.equals(teardown));
        assertFalse(dirty.equals(teardown));
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
    void removalBudgetOnlyConsumesSuccessfullyAdmittedDrops() {
        var removals = new LongOpenHashSet(new long[] {1L, 2L, 3L, 4L});

        int admitted = RtTerrain.admitEvictions(removals, 2, key -> key != 2L);

        assertEquals(2, admitted);
        assertEquals(2, removals.size());
        assertTrue(removals.contains(2L));
    }

    private static final class MutablePublication implements RetainedPublication {
        private final java.util.List<Runnable> callbacks = new java.util.ArrayList<>();
        private boolean visible;
        @Override public boolean isVisible() { return visible; }
        @Override public void whenVisible(Runnable callback) {
            if (visible) callback.run();
            else callbacks.add(callback);
        }
        void makeVisible() {
            visible = true;
            callbacks.forEach(Runnable::run);
            callbacks.clear();
        }
    }

    @Test
    void undesiredStalePublicationRematerializesItsDrop() {
        assertTrue(RtTerrain.shouldRequeueDropAfterClearedPublication(false, true, false));
        assertTrue(RtTerrain.shouldRequeueDropAfterClearedPublication(false, false, true));
        assertFalse(RtTerrain.shouldRequeueDropAfterClearedPublication(true, true, true));
        assertFalse(RtTerrain.shouldRequeueDropAfterClearedPublication(false, false, false));
    }

    @Test
    void laterDropCoalescesAnUnsubmittedPutForTheSameSection() {
        assertEquals(java.util.List.of(7L), RtTerrain.latestGroupKeys(java.util.List.of(7L, 7L)));
    }

    @Test
    void emptyDirtyGroupWaitsWhenAnyMemberStillRequiresNativeOrdering() {
        assertTrue(RtTerrain.emptyDirtyGroupCanCompleteImmediately(false, false));
        assertFalse(RtTerrain.emptyDirtyGroupCanCompleteImmediately(true, false));
        assertFalse(RtTerrain.emptyDirtyGroupCanCompleteImmediately(false, true));
    }
}
