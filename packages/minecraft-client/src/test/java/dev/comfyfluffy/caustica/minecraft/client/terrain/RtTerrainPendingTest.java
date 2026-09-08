package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtTerrainPendingTest {
    @Test
    void readinessProbeExcludesInvalidatedGroupsUntilAllReplacementsComplete() {
        var updates = new TerrainUpdates<String>();
        assertFalse(updates.hasReadyGroup());
        updates.want(1);
        updates.want(2);
        updates.rebuild(List.of(1L, 2L));
        updates.complete(updates.sections.get(1).request, "first");
        assertFalse(updates.hasReadyGroup());
        updates.complete(updates.sections.get(2).request, "second");
        assertTrue(updates.hasReadyGroup());
        updates.invalidate(List.of(1L));
        assertFalse(updates.hasReadyGroup());
        updates.dirty(List.of(1L));
        updates.complete(updates.sections.get(1).request, "replacement first");
        assertFalse(updates.hasReadyGroup());
        updates.complete(updates.sections.get(2).request, "replacement second");
        assertTrue(updates.hasReadyGroup());
        updates.published(updates.ready());
        assertFalse(updates.hasReadyGroup());
    }

    @Test
    void readinessProbePermitsAnotherPublicationAfterPendingWorkArrives() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        updates.want(2);
        updates.complete(updates.sections.get(1).request, "first");
        var publishing = updates.ready();
        updates.complete(updates.sections.get(2).request, "arrived during publication");
        updates.published(publishing);
        assertTrue(updates.hasReadyGroup());
        updates.published(updates.ready());
        assertFalse(updates.hasReadyGroup());
        updates.remove(1);
        assertTrue(updates.hasReadyGroup());
        updates.clear();
        assertFalse(updates.hasReadyGroup());
    }

    @Test
    void dirtyAndPublicationHaveOneAtomicGroupAcceptanceBoundary() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        updates.complete(updates.sections.get(1).request, "first");
        var beforeDirty = updates.ready();
        updates.invalidate(List.of(1L));
        assertFalse(updates.claimPublication(beforeDirty));

        updates.dirty(List.of(1L));
        updates.complete(updates.sections.get(1).request, "second");
        var accepted = updates.ready();
        assertTrue(updates.claimPublication(accepted));
        assertFalse(updates.hasReadyGroup());
        assertTrue(updates.ready().isEmpty());
        updates.releasePublication(accepted);
        assertEquals(accepted, updates.ready());
        assertTrue(updates.claimPublication(accepted));
        updates.invalidate(List.of(1L));
        updates.published(accepted);
        updates.releasePublication(accepted);
        assertTrue(updates.isReady(1));
        updates.dirty(List.of(1L));
        assertTrue(updates.awaitingExtraction(updates.sections.get(1).request));
        assertTrue(updates.ready().isEmpty());
    }

    @Test
    void failedBatchClaimReleasesEarlierGroupsWithoutRevivingInvalidatedGroups() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        updates.want(2);
        updates.complete(updates.sections.get(1).request, "first");
        updates.complete(updates.sections.get(2).request, "second");
        var batch = updates.ready();
        updates.invalidate(List.of(2L));
        assertFalse(updates.claimPublication(batch));
        var surviving = updates.ready();
        assertEquals(1, surviving.size());
        assertTrue(updates.claimPublication(surviving));
        updates.releasePublication(surviving);
        assertFalse(updates.claimPublication(List.of(batch.get(1))));
    }

    @Test
    void extractionReservationRejectsConcurrentReplacementBeforeAndAfterCapture() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        var beforeCapture = updates.sections.get(1).request;
        assertTrue(beforeCapture.reserve());
        assertFalse(beforeCapture.reserve());
        updates.dirty(List.of(1L));
        assertFalse(beforeCapture.extracted());
        assertFalse(updates.complete(beforeCapture, "stale capture"));

        var afterCapture = updates.sections.get(1).request;
        assertTrue(afterCapture.reserve());
        assertTrue(afterCapture.extracted());
        updates.invalidate(List.of(1L));
        updates.dispatched(afterCapture);
        updates.retry(afterCapture);
        assertFalse(updates.awaitingExtraction(afterCapture));
        assertFalse(updates.complete(afterCapture, "stale build"));
    }

    @Test
    void immediateInvalidationExcludesWholeCompletedGroupBeforeWorkerRegrouping() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        updates.want(2);
        updates.rebuild(List.of(1L, 2L));
        updates.complete(updates.sections.get(1).request, "A");
        updates.complete(updates.sections.get(2).request, "B");
        assertEquals(1, updates.ready().size());
        updates.invalidate(List.of(1L));
        assertTrue(updates.ready().isEmpty());
        updates.dirty(List.of(1L));
        assertSame(updates.sections.get(1).request.group, updates.sections.get(2).request.group);
        updates.complete(updates.sections.get(1).request, "latest A");
        assertTrue(updates.ready().isEmpty());
        updates.complete(updates.sections.get(2).request, "latest B");
        updates.published(updates.ready());
        assertTrue(updates.isReady(1));
        assertTrue(updates.isReady(2));
        updates.clear();
        assertFalse(updates.isReady(1));
    }

    @Test
    void dispatchRetryAndCompletionOnlyPublishPendingDeltas() {
        var pending = new LinkedHashMap<Long, TerrainUpdates.Request<String>>();
        var updates = updates(pending);
        updates.want(1);
        var request = updates.sections.get(1).request;
        assertEquals(List.of(request), List.copyOf(pending.values()));
        assertTrue(updates.awaitingExtraction(request));
        updates.dispatched(request);
        assertTrue(pending.isEmpty());
        assertFalse(updates.awaitingExtraction(request));
        updates.retry(request);
        assertEquals(List.of(request), List.copyOf(pending.values()));
        assertTrue(updates.awaitingExtraction(request));
        updates.dispatched(request);
        updates.complete(request, "mesh");
        assertTrue(pending.isEmpty());
        assertFalse(updates.awaitingExtraction(request));
    }

    @Test
    void supersededGroupsReplaceAllPendingMembersAndRejectLateResults() {
        var pending = new LinkedHashMap<Long, TerrainUpdates.Request<String>>();
        var updates = updates(pending);
        updates.want(1);
        updates.want(2);
        updates.rebuild(List.of(1L, 2L));
        var old = updates.sections.get(1).request;
        updates.dispatched(old);
        updates.rebuild(List.of(2L));
        assertEquals(2, pending.size());
        assertFalse(pending.containsValue(old));
        assertFalse(updates.awaitingExtraction(old));
        assertFalse(updates.complete(old, "obsolete"));
        assertSame(updates.sections.get(1).request.group, updates.sections.get(2).request.group);
        updates.remove(1);
        assertEquals(List.of(updates.sections.get(2).request), List.copyOf(pending.values()));
        updates.clear();
        assertTrue(pending.isEmpty());
    }

    @Test
    void readyMembershipTracksReplacementRemovalAndPublication() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        updates.want(2);
        updates.want(3);
        var first = updates.sections.get(1).request;
        updates.complete(first, "ready before replacement");
        assertEquals(List.of(first.group), updates.ready());
        updates.rebuild(List.of(1L, 2L));
        assertTrue(updates.ready().isEmpty());
        assertFalse(updates.complete(first, "obsolete"));
        updates.complete(updates.sections.get(1).request, "replacement");
        assertTrue(updates.ready().isEmpty());
        updates.remove(2);
        assertTrue(updates.ready().isEmpty());
        updates.complete(updates.sections.get(1).request, "survivor");
        assertEquals(1, updates.ready().size());
        assertEquals(2, updates.ready().getFirst().requests.size());
        updates.published(updates.ready());
        assertTrue(updates.ready().isEmpty());
        assertFalse(updates.sections.containsKey(2));
        updates.complete(updates.sections.get(3).request, "last");
        assertEquals(1, updates.ready().size());
        updates.clear();
        assertTrue(updates.ready().isEmpty());
    }

    private static TerrainUpdates<String> updates(LinkedHashMap<Long, TerrainUpdates.Request<String>> pending) {
        return new TerrainUpdates<>(value -> { }, (key, request) -> {
            if (request == null) pending.remove(key);
            else pending.put(key, request);
        });
    }
}
