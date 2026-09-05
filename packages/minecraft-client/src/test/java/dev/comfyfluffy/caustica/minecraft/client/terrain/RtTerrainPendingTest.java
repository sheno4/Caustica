package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtTerrainPendingTest {
    @Test
    void dispatchRetryAndCompletionOnlyVisitPendingWork() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        var request = updates.sections.get(1).request;
        assertEquals(List.of(request), List.copyOf(updates.pending()));
        updates.dispatched(request);
        assertTrue(updates.pending().isEmpty());
        updates.retry(request);
        assertEquals(List.of(request), List.copyOf(updates.pending()));
        updates.dispatched(request);
        updates.complete(request, "mesh");
        assertTrue(updates.pending().isEmpty());
    }

    @Test
    void supersededGroupsReplaceAllPendingMembersAndRejectLateResults() {
        var updates = new TerrainUpdates<String>();
        updates.want(1);
        updates.want(2);
        updates.rebuild(List.of(1L, 2L));
        var old = updates.sections.get(1).request;
        updates.dispatched(old);
        updates.rebuild(List.of(2L));
        assertEquals(2, updates.pending().size());
        assertFalse(updates.pending().contains(old));
        assertFalse(updates.complete(old, "obsolete"));
        updates.remove(1);
        assertEquals(List.of(updates.sections.get(2).request), List.copyOf(updates.pending()));
        updates.clear();
        assertTrue(updates.pending().isEmpty());
    }
}
