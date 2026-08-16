package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
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
    void equalValuesInDifferentDomainsOwnIndependentResidents() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        SceneGeometryKey entity = new SceneGeometryKey(1, 42);
        SceneGeometryKey terrain = new SceneGeometryKey(2, 42);
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(entity, payload())));
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Put(terrain, payload())));

        assertEquals(2, scheduler.startable().size());
    }

    @Test
    void staleRevisionCannotReplaceANewerAcceptedGroup() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Drop(10)));
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Drop(20)));

        assertEquals(2L, scheduler.startable().getFirst().prepared().revision);
    }

    @Test
    void dependentPlacementMustShareItsResidentGroup() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        assertThrows(IllegalArgumentException.class, () -> scheduler.submit(
                group(SECOND, new RtSceneGeometryManager.Place(3, 10, identity(), 0xff))));
    }

    @Test
    void initialResidentAndPlacementPublishAsOneGroup() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));

        assertEquals(FIRST, scheduler.startable().getFirst().key());
    }

    @Test
    void newerUnpublishedInitialGroupRetainsItsResidentDependencies() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        float[] moved = identity();
        moved[3] = 128f;
        scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, moved, 0xff)));

        RtSceneGeometryManager.PreparedGroup prepared = scheduler.startable().getFirst().prepared();
        assertEquals(2L, prepared.revision);
        assertEquals(128f, prepared.diff.placements.get(SceneGeometryKey.of(3)).transform[3]);
    }

    @Test
    void pendingCloudWindowUpdateReplacesTheRunningDesiredInstances() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, identity(), 0xff),
                new RtSceneGeometryManager.Place(4, 10, identity(), 0xff)));
        RtSceneGeometryManager.GroupRun running = scheduler.startable().getFirst();
        scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(4, 10, identity(), 0xff),
                new RtSceneGeometryManager.Place(5, 10, identity(), 0xff),
                new RtSceneGeometryManager.Remove(3)));

        scheduler.complete(running.prepared().barrier, true);
        RtSceneGeometryManager.PreparedGroup finalUpdate = scheduler.startable().getFirst().prepared();

        assertEquals(2L, finalUpdate.revision);
        assertTrue(finalUpdate.diff.placements.containsKey(SceneGeometryKey.of(4)));
        assertTrue(finalUpdate.diff.placements.containsKey(SceneGeometryKey.of(5)));
        assertTrue(finalUpdate.diff.removes.contains(SceneGeometryKey.of(3)));
    }

    @Test
    void overlappingIndependentGroupsRemainSeparateAndSerialize() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(1, payload()),
                new RtSceneGeometryManager.Put(2, payload())));
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Put(1, payload())));

        RtSceneGeometryManager.GroupRun first = scheduler.startable().getFirst();
        assertEquals(FIRST, first.key());
        assertEquals(List.of(), scheduler.startable());
        scheduler.complete(first.prepared().barrier, true);
        assertEquals(SECOND, scheduler.startable().getFirst().key());
    }

    @Test
    void placementCannotDependOnUnpublishedResident() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        assertThrows(IllegalArgumentException.class, () -> scheduler.submit(
                group(FIRST, new RtSceneGeometryManager.Place(3, 2, identity(), 0xff))));

        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(2, payload()),
                new RtSceneGeometryManager.Place(3, 2, identity(), 0xff)));
        assertEquals(1, scheduler.startable().size(), "a rejected group must not advance its accepted revision");
    }

    @Test
    void invalidBatchDoesNotQueueItsEarlierGroups() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();

        assertThrows(IllegalArgumentException.class, () -> scheduler.submitAll(List.of(
                group(FIRST, new RtSceneGeometryManager.Put(10, payload())),
                group(SECOND, new RtSceneGeometryManager.Place(3, 20, identity(), 0xff))), null, null));

        assertEquals(List.of(), scheduler.startable());
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        assertEquals(1, scheduler.startable().size(), "the rejected batch must not advance accepted revisions");
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
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> null);
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

    @Test
    void stablePlacementRetainsTransformHistoryAcrossAnOriginRebase() {
        float[] previous = {1, 0, 0, 4, 0, 1, 0, 5, 0, 0, 1, 6};
        float[] rebased = RtSceneGeometryManager.rebasePreviousTransform(previous,
                new SceneOrigin(100, 20, -4), new SceneOrigin(90, 30, -10));

        assertTrue(!RtSceneGeometryManager.resetTransformMotion(true));
        assertEquals(14f, rebased[3]);
        assertEquals(-5f, rebased[7]);
        assertEquals(12f, rebased[11]);
    }

    private static RtSceneGeometryManager.GeometryUpdateGroup group(RtSceneGeometryManager.GroupKey key,
                                                                      RtSceneGeometryManager.GeometryOperation... operations) {
        return group(key, 1L, operations);
    }

    private static RtSceneGeometryManager.GeometryUpdateGroup group(RtSceneGeometryManager.GroupKey key, long revision,
                                                                      RtSceneGeometryManager.GeometryOperation... operations) {
        return new RtSceneGeometryManager.GeometryUpdateGroup(key, revision, List.of(operations));
    }

    private static RtSceneGeometryManager.ProviderPayload payload() {
        SceneMesh mesh = new SceneMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[] {0, 0, 1, 0, 0, 1},
                List.of(SceneMesh.TriangleSurface.surface(new MaterialHandle(ResourceId.of("test", "surface")))));
        return new RtSceneGeometryManager.ProviderPayload(mesh);
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    }
}
