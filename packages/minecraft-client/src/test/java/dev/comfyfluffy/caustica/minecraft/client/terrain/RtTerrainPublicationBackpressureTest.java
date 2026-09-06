package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtTerrainPublicationBackpressureTest {
    @Test
    void neighborsRemainVisibleUntilEveryReplacementIsReady() {
        var terrain = published(1, 2);
        terrain.rebuild(List.of(1L, 2L));
        terrain.complete(terrain.sections.get(1L).request, "new A");
        assertTrue(terrain.ready().isEmpty());
        assertTrue(terrain.sections.get(1L).ready);
        assertTrue(terrain.sections.get(2L).ready);
        terrain.complete(terrain.sections.get(2L).request, "new B");
        var ready = terrain.ready();
        assertEquals(1, ready.size());
        assertEquals(2, ready.getFirst().requests.size());
        terrain.published(ready);
        assertNull(terrain.sections.get(1L).request);
        assertNull(terrain.sections.get(2L).request);
    }

    @Test
    void overlappingEditRebuildsTheEntireUnionAndRejectsOldResults() {
        var terrain = published(1, 2, 3);
        terrain.rebuild(List.of(1L, 2L));
        var oldA = terrain.sections.get(1L).request;
        terrain.complete(oldA, "obsolete A");
        terrain.rebuild(List.of(2L, 3L));
        assertFalse(terrain.complete(oldA, "late A"));
        assertTrue(terrain.ready().isEmpty());
        for (long key : new long[] {1, 2, 3}) terrain.complete(terrain.sections.get(key).request, "latest");
        assertEquals(3, terrain.ready().getFirst().requests.size());
    }

    @Test
    void unrelatedReadySectionDoesNotWaitForSlowNeighborGroup() {
        var terrain = published(1, 2, 3);
        terrain.rebuild(List.of(1L, 2L));
        terrain.rebuild(List.of(3L));
        terrain.complete(terrain.sections.get(3L).request, "unrelated");
        assertEquals(3L, terrain.ready().getFirst().requests.getFirst().section.key);
    }

    @Test
    void removalRejectsLateBuildAndPublishesWithRemainingNeighbors() {
        var terrain = published(1, 2);
        terrain.rebuild(List.of(1L, 2L));
        var removedBuild = terrain.sections.get(1L).request;
        terrain.remove(1L);
        assertFalse(terrain.complete(removedBuild, "late"));
        assertTrue(terrain.ready().isEmpty());
        terrain.complete(terrain.sections.get(2L).request, "remaining");
        var ready = terrain.ready();
        assertEquals(2, ready.getFirst().requests.size());
        terrain.published(ready);
        assertFalse(terrain.sections.containsKey(1L));
        assertTrue(terrain.sections.get(2L).ready);
    }

    @Test
    void publicationNeverSplitsANeighborGroup() {
        var terrain = published(1, 2, 3);
        terrain.rebuild(List.of(1L, 2L, 3L));
        for (long key : new long[] {1, 2, 3}) terrain.complete(terrain.sections.get(key).request, "new");
        assertEquals(3, terrain.ready().getFirst().requests.size());
    }

    @Test
    void emptySectionBecomesReadyOnlyAfterPublication() {
        var terrain = new TerrainUpdates<String>();
        terrain.want(1L);
        terrain.complete(terrain.sections.get(1L).request, null);
        assertFalse(terrain.sections.get(1L).ready);
        terrain.published(terrain.ready());
        assertTrue(terrain.sections.get(1L).ready);
    }

    @Test
    void resetRejectsCompletionsFromPreviousWorldAtSameCoordinates() {
        var terrain = new TerrainUpdates<String>();
        terrain.want(1L);
        var old = terrain.sections.get(1L).request;
        terrain.clear();
        terrain.want(1L);
        assertFalse(terrain.complete(old, "previous world"));
        assertTrue(terrain.ready().isEmpty());
    }

    @Test
    void initialChunkDirtinessDoesNotMakeReadySectionsWaitForUnloadedNeighbors() {
        var terrain = new TerrainUpdates<String>();
        terrain.want(1L);
        terrain.want(2L);
        terrain.want(3L);
        terrain.dirty(List.of(1L, 2L));
        terrain.dirty(List.of(2L, 3L));
        terrain.complete(terrain.sections.get(1L).request, "player section");
        var ready = terrain.ready();
        assertEquals(1, ready.size());
        assertEquals(1, ready.getFirst().requests.size());
        terrain.published(ready);
        assertTrue(terrain.sections.get(1L).ready);
        assertFalse(terrain.sections.get(2L).ready);
    }

    @Test
    void visibleNeighborsStayAtomicWhileNewBoundarySectionBuildsIndependently() {
        var terrain = published(1, 2);
        terrain.want(3L);
        terrain.dirty(List.of(1L, 2L, 3L));
        terrain.complete(terrain.sections.get(1L).request, "new A");
        assertTrue(terrain.ready().isEmpty());
        terrain.complete(terrain.sections.get(2L).request, "new B");
        assertEquals(2, terrain.ready().getFirst().requests.size());
    }

    @Test
    void allReadySectionsPublishTogetherDespiteAnEmptyBacklog() {
        var terrain = new TerrainUpdates<String>();
        for (long key = 10_000; key < 20_000; key++) {
            terrain.want(key);
            terrain.complete(terrain.sections.get(key).request, null);
        }
        terrain.want(1L);
        terrain.complete(terrain.sections.get(1L).request, "player terrain");
        var admitted = terrain.ready();
        assertEquals(10_001, admitted.size());
        terrain.published(admitted);
        assertTrue(terrain.sections.get(1L).ready);
    }

    @Test
    void supersededAndClearedGroupsReleasePreparedResourcesExactlyOnce() {
        var discarded = new java.util.ArrayList<String>();
        var terrain = new TerrainUpdates<String>(discarded::add);
        terrain.want(1L);
        terrain.want(2L);
        terrain.rebuild(List.of(1L, 2L));
        terrain.complete(terrain.sections.get(1L).request, "prepared A");
        terrain.complete(terrain.sections.get(2L).request, "prepared B");
        terrain.rebuild(List.of(1L, 2L));
        assertEquals(java.util.Set.of("prepared A", "prepared B"), java.util.Set.copyOf(discarded));
        assertEquals(2, discarded.size());
        terrain.complete(terrain.sections.get(1L).request, "prepared C");
        terrain.clear();
        assertEquals(3, discarded.size());
        assertEquals("prepared C", discarded.getLast());
    }

    @Test
    void acceptedEditTransfersPreparedOwnershipWithoutDiscardingIt() {
        var discarded = new java.util.ArrayList<String>();
        var terrain = new TerrainUpdates<String>(discarded::add);
        terrain.want(1L);
        terrain.complete(terrain.sections.get(1L).request, "prepared A");
        terrain.published(terrain.ready());
        terrain.clear();
        assertTrue(discarded.isEmpty());
    }

    @Test
    void allReadyMeshesPublishAlongsideTheEmptyBacklog() {
        var terrain = new TerrainUpdates<String>();
        for (long key = 0; key < 10_000; key++) {
            terrain.want(key);
            terrain.complete(terrain.sections.get(key).request, null);
        }
        for (long key = 10_000; key < 10_010; key++) {
            terrain.want(key);
            terrain.complete(terrain.sections.get(key).request, "mesh");
        }
        var admitted = terrain.ready();
        assertEquals(10_010, admitted.size());
        terrain.published(admitted);
        assertNull(terrain.sections.get(0L).request);
        assertNull(terrain.sections.get(10_007L).request);
        assertNull(terrain.sections.get(10_008L).request);
        assertNull(terrain.sections.get(10_009L).request);
    }

    @Test
    void emptyMemberWaitsForItsRealNeighbors() {
        var terrain = published(1, 2, 3);
        terrain.rebuild(List.of(1L, 2L, 3L));
        terrain.complete(terrain.sections.get(1L).request, null);
        terrain.complete(terrain.sections.get(2L).request, "mesh A");
        assertTrue(terrain.ready().isEmpty());
        terrain.complete(terrain.sections.get(3L).request, "mesh B");
        var admitted = terrain.ready();
        assertEquals(1, admitted.size());
        assertEquals(3, admitted.getFirst().requests.size());
    }

    @Test
    void allRemovalsPublishAtTheSameBoundary() {
        var terrain = published(1, 2, 3);
        terrain.remove(1L);
        terrain.remove(2L);
        terrain.remove(3L);
        var admitted = terrain.ready();
        assertEquals(3, admitted.size());
        terrain.published(admitted);
        assertFalse(terrain.sections.containsKey(1L));
        assertFalse(terrain.sections.containsKey(2L));
        assertFalse(terrain.sections.containsKey(3L));
    }

    private static TerrainUpdates<String> published(long... keys) {
        var terrain = new TerrainUpdates<String>();
        for (long key : keys) {
            terrain.want(key);
            terrain.complete(terrain.sections.get(key).request, "old");
        }
        terrain.published(terrain.ready());
        return terrain;
    }
}
