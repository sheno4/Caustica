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
        assertTrue(terrain.ready(8, section -> section.key).isEmpty());
        assertTrue(terrain.sections.get(1L).ready);
        assertTrue(terrain.sections.get(2L).ready);
        terrain.complete(terrain.sections.get(2L).request, "new B");
        var ready = terrain.ready(8, section -> section.key);
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
        assertTrue(terrain.ready(8, section -> section.key).isEmpty());
        for (long key : new long[] {1, 2, 3}) terrain.complete(terrain.sections.get(key).request, "latest");
        assertEquals(3, terrain.ready(8, section -> section.key).getFirst().requests.size());
    }

    @Test
    void unrelatedReadySectionDoesNotWaitForSlowNeighborGroup() {
        var terrain = published(1, 2, 3);
        terrain.rebuild(List.of(1L, 2L));
        terrain.rebuild(List.of(3L));
        terrain.complete(terrain.sections.get(3L).request, "unrelated");
        assertEquals(3L, terrain.ready(8, section -> section.key).getFirst().requests.getFirst().section.key);
    }

    @Test
    void removalRejectsLateBuildAndPublishesWithRemainingNeighbors() {
        var terrain = published(1, 2);
        terrain.rebuild(List.of(1L, 2L));
        var removedBuild = terrain.sections.get(1L).request;
        terrain.remove(1L);
        assertFalse(terrain.complete(removedBuild, "late"));
        assertTrue(terrain.ready(8, section -> section.key).isEmpty());
        terrain.complete(terrain.sections.get(2L).request, "remaining");
        var ready = terrain.ready(8, section -> section.key);
        assertEquals(2, ready.getFirst().requests.size());
        terrain.published(ready);
        assertFalse(terrain.sections.containsKey(1L));
        assertTrue(terrain.sections.get(2L).ready);
    }

    @Test
    void publicationBudgetNeverSplitsANeighborGroup() {
        var terrain = published(1, 2, 3);
        terrain.rebuild(List.of(1L, 2L, 3L));
        for (long key : new long[] {1, 2, 3}) terrain.complete(terrain.sections.get(key).request, "new");
        assertEquals(3, terrain.ready(1, section -> section.key).getFirst().requests.size());
    }

    @Test
    void emptySectionBecomesReadyOnlyAfterPublication() {
        var terrain = new TerrainUpdates<String>();
        terrain.want(1L);
        terrain.complete(terrain.sections.get(1L).request, null);
        assertFalse(terrain.sections.get(1L).ready);
        terrain.published(terrain.ready(8, section -> section.key));
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
        assertTrue(terrain.ready(8, section -> section.key).isEmpty());
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
        var ready = terrain.ready(8, section -> section.key);
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
        assertTrue(terrain.ready(8, section -> section.key).isEmpty());
        terrain.complete(terrain.sections.get(2L).request, "new B");
        assertEquals(2, terrain.ready(8, section -> section.key).getFirst().requests.size());
    }

    @Test
    void nearPlayerPublicationPassesThousandsOfOlderReadyEmptySections() {
        var terrain = new TerrainUpdates<String>();
        for (long key = 10_000; key < 20_000; key++) {
            terrain.want(key);
            terrain.complete(terrain.sections.get(key).request, null);
        }
        terrain.want(1L);
        terrain.complete(terrain.sections.get(1L).request, "player terrain");
        var admitted = terrain.ready(8, section -> section.key);
        assertEquals(1L, admitted.getFirst().requests.getFirst().section.key);
        terrain.published(admitted);
        assertTrue(terrain.sections.get(1L).ready);
    }

    private static TerrainUpdates<String> published(long... keys) {
        var terrain = new TerrainUpdates<String>();
        for (long key : keys) {
            terrain.want(key);
            terrain.complete(terrain.sections.get(key).request, "old");
        }
        terrain.published(terrain.ready(Integer.MAX_VALUE, section -> section.key));
        return terrain;
    }
}
