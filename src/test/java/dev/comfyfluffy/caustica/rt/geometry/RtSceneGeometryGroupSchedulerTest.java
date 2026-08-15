package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSceneGeometryGroupSchedulerTest {
    private static final ResourceId SOURCE = ResourceId.of("test", "entity");
    private static final RtSceneGeometryManager.GroupKey FIRST = new RtSceneGeometryManager.GroupKey(SOURCE, 1L);
    private static final RtSceneGeometryManager.GroupKey SECOND = new RtSceneGeometryManager.GroupKey(SOURCE, 2L);

    @Test
    void overlappingGroupsReserveGlobalResidentOwnership() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        assertEquals(1, scheduler.startable().size());
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Put(10, payload())));
        assertEquals(0, scheduler.startable().size());
    }

    @Test
    void disjointGroupsStartConcurrently() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Put(20, payload())));
        assertEquals(2, scheduler.startable().size());
    }

    @Test
    void placementMergesWithItsPendingResidentPut() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        assertEquals(1, scheduler.startable().size());
    }

    @Test
    void placementCannotDependOnUnpublishedResident() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        assertThrows(IllegalArgumentException.class, () -> scheduler.submit(
                group(FIRST, new RtSceneGeometryManager.Place(3, 2, identity(), 0xff))));
    }

    @Test
    void clearingSourceCancelsRunningGroupAndReleasesItsReservationAtTerminal() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        RtSceneGeometryManager.PreparedGroup running = scheduler.startable().getFirst().prepared();

        scheduler.clearSource(SOURCE);
        assertTrue(scheduler.cancelled(running.barrier));

        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Put(10, payload())));
        assertEquals(0, scheduler.startable().size());
        scheduler.complete(running.barrier, false);
        assertEquals(1, scheduler.startable().size());
    }

    @Test
    void groupFailureStaysVisibleOnRenderThread() {
        RtSceneGeometryManager.FailureLatch failures = new RtSceneGeometryManager.FailureLatch();
        RuntimeException cause = new RuntimeException("build failed");
        failures.record(cause);

        IllegalStateException reported = assertThrows(IllegalStateException.class, failures::throwIfPresent);
        assertEquals(cause, reported.getCause());
    }

    @Test
    void progressPublishesAGroupWithoutFrameAssembly() {
        RtSceneGeometryManager manager = new RtSceneGeometryManager(handle -> null);
        AtomicReference<RtSceneGeometryManager.PublicationAck> published = new AtomicReference<>();
        RtSceneGeometryManager.GeometryUpdateGroup update = group(FIRST, new RtSceneGeometryManager.Drop(10));

        manager.submit(List.of(update), published::set);
        manager.progress(null);
        assertNull(published.get());

        manager.progress(null);
        assertEquals(FIRST, published.get().key());
        assertEquals(1L, published.get().revision());
    }

    @Test
    void placementRebasesItsTranslationFromItsAuthoredOrigin() {
        RtSceneGeometryManager.Placement placement = new RtSceneGeometryManager.Placement(10,
                new float[] {1, 0, 0, 4, 0, 1, 0, 5, 0, 0, 1, 6}, 0xff,
                new SceneOrigin(100, 20, -4));
        float[] transform = placement.transformFor(new SceneOrigin(90, 30, -10));
        assertEquals(14f, transform[3]);
        assertEquals(-5f, transform[7]);
        assertEquals(12f, transform[11]);
    }

    private static RtSceneGeometryManager.GeometryUpdateGroup group(RtSceneGeometryManager.GroupKey key,
                                                                      RtSceneGeometryManager.GeometryOperation... operations) {
        return new RtSceneGeometryManager.GeometryUpdateGroup(key, 1L, List.of(operations));
    }

    private static RtSceneGeometryManager.IndexedPayload payload() {
        return new RtSceneGeometryManager.IndexedPayload(new RtSceneGeometryManager.PackedInput(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                new float[] {0, 0, 1, 0, 0, 1}, new float[12], new int[] {1, 0, 0},
                0, 1, RtSceneGeometryManager.BuildClass.STATIC, 0));
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    }
}
