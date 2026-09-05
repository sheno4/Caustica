package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ScenePublicationQueueTest {
    @Test
    void wholeGroupWaitsForPreparationWhileUnrelatedReadyEditCommits() {
        var queue = new ScenePublicationQueue<String, List<String>>();
        var neighbors = queue.add(Set.of("mesh A", "mesh B", "light A"), false,
                List.of("new A", "new B", "new light"));
        var unrelated = queue.add(Set.of("mesh C"), false, List.of("new C"));
        unrelated.ready = true;
        var visible = new ArrayList<List<String>>();
        queue.commitReady(visible::add);
        assertEquals(List.of(List.of("new C")), visible);
        neighbors.ready = true;
        queue.commitReady(visible::add);
        assertEquals(List.of(List.of("new C"), List.of("new A", "new B", "new light")), visible);
    }

    @Test
    void overlappingReplacementCannotOvertakeItsNeighborsOrBeResurrectedByLateCompletion() {
        var queue = new ScenePublicationQueue<String, String>();
        var older = queue.add(Set.of("A", "B"), false, "replace A and B");
        var removal = queue.add(Set.of("A"), false, "remove A");
        removal.ready = true;
        var visible = new ArrayList<String>();
        queue.commitReady(visible::add);
        assertTrue(visible.isEmpty());
        older.ready = true;
        queue.commitReady(visible::add);
        assertEquals(List.of("replace A and B", "remove A"), visible);
        queue.commitReady(visible::add);
        assertEquals(2, visible.size());
    }

    @Test
    void sceneReplacementFormsABarrierForEarlierAndLaterEdits() {
        var queue = new ScenePublicationQueue<String, Integer>();
        var first = queue.add(Set.of("A"), false, 1);
        var replacement = queue.add(Set.of(), true, 2);
        var last = queue.add(Set.of("B"), false, 3);
        replacement.ready = true;
        last.ready = true;
        var visible = new ArrayList<Integer>();
        queue.commitReady(visible::add);
        assertTrue(visible.isEmpty());
        first.ready = true;
        queue.commitReady(visible::add);
        assertEquals(List.of(1, 2, 3), visible);
    }
}
