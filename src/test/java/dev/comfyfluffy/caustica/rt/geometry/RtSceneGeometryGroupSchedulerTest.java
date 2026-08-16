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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void disjointRevisionsOfTheSameGroupSerialize() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Put(1, payload())));
        RtSceneGeometryManager.PreparedGroup first = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Put(2, payload())));

        assertEquals(List.of(), scheduler.startable());
        scheduler.complete(first.barrier, true);
        assertEquals(2L, scheduler.startable().getFirst().prepared().revision);
    }

    @Test
    void placementRetargetMaintainsStableForwardAndReverseLinks() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.ResidentId firstResident = resident(SOURCE, 10);
        RtSceneGeometryManager.ResidentId secondResident = resident(SOURCE, 20);
        RtSceneGeometryManager.InstanceId instance = instance(SOURCE, 3);
        scheduler.putPublishedResident(firstResident, new FakeResident(), false);
        scheduler.putPublishedResident(secondResident, new FakeResident(), false);
        scheduler.putPublishedPlacement(instance,
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), identity(), 0xff));
        RtSceneGeometryManager.PublishedPlacement published = scheduler.publishedPlacement(instance);

        scheduler.putPublishedPlacement(instance,
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(20), identity(), 0xff));

        assertSame(published, scheduler.publishedPlacement(instance));
        assertEquals(0, scheduler.publishedSlot(firstResident).placements.size());
        assertEquals(1, scheduler.publishedSlot(secondResident).placements.size());
        assertSame(scheduler.publishedSlot(secondResident), published.resident);
        scheduler.assertPublishedIndexConsistent();

        scheduler.removePublishedPlacement(instance);
        assertNull(scheduler.publishedPlacement(instance));
        assertEquals(0, scheduler.publishedSlot(secondResident).placements.size());
        scheduler.assertPublishedIndexConsistent();
    }

    @Test
    void dropRequiresEveryMemberToBeRemovedOrRetargeted() {
        RtSceneGeometryManager.GroupScheduler survivor = publishedPair();
        assertThrows(IllegalArgumentException.class, () -> survivor.submit(group(FIRST,
                new RtSceneGeometryManager.Drop(10))));

        RtSceneGeometryManager.GroupScheduler removed = publishedPair();
        removed.submit(group(FIRST, new RtSceneGeometryManager.Drop(10), new RtSceneGeometryManager.Remove(3)));
        assertEquals(1, removed.startable().size());

        RtSceneGeometryManager.GroupScheduler retargeted = publishedPair();
        retargeted.putPublishedResident(resident(SOURCE, 20), new FakeResident(), false);
        retargeted.submit(group(FIRST, new RtSceneGeometryManager.Drop(10),
                new RtSceneGeometryManager.Place(3, 20, identity(), 0xff)));
        assertEquals(1, retargeted.startable().size());
    }

    @Test
    void queuedBarrierIsRevalidatedAfterRunningBarrierChangesPublishedState() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.ResidentId resident = resident(SOURCE, 10);
        scheduler.putPublishedResident(resident, new FakeResident(), false);
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Drop(10)));
        RtSceneGeometryManager.PreparedGroup dropping = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));

        scheduler.removePublishedResident(resident);
        scheduler.complete(dropping.barrier, true);

        assertThrows(IllegalArgumentException.class, scheduler::startable);
    }

    @Test
    void queuedDropIsRevalidatedAfterRunningPlacementPublishes() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.ResidentId resident = resident(SOURCE, 10);
        RtSceneGeometryManager.InstanceId instance = instance(SOURCE, 3);
        RtSceneGeometryManager.Placement placement =
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), identity(), 0xff);
        scheduler.putPublishedResident(resident, new FakeResident(), false);
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        RtSceneGeometryManager.PreparedGroup placing = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(SECOND, new RtSceneGeometryManager.Drop(10)));

        scheduler.putPublishedPlacement(instance, placement);
        scheduler.complete(placing.barrier, true);

        assertThrows(IllegalArgumentException.class, scheduler::startable);
    }

    @Test
    void compatibleDoubleReplacementKeepsOnlyTheImmediatePreviousResident() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.ResidentId id = resident(SOURCE, 10);
        FakeResident first = new FakeResident();
        FakeResident second = new FakeResident();
        FakeResident third = new FakeResident();
        scheduler.putPublishedResident(id, first, false);

        assertEquals(List.of(), scheduler.putPublishedResident(id, second, true));
        assertSame(first, scheduler.publishedSlot(id).previous);
        assertEquals(List.of(first), scheduler.putPublishedResident(id, third, true));
        assertSame(second, scheduler.publishedSlot(id).previous);
        assertSame(third, scheduler.publishedSlot(id).current);

        AtomicReference<RtSceneGeometryManager.GroupResident> drained = new AtomicReference<>();
        scheduler.drainPreviousResidents(drained::set);
        assertSame(second, drained.get());
        assertNull(scheduler.publishedSlot(id).previous);
    }

    @Test
    void incompatibleReplacementRetiresCurrentAndOutstandingPrevious() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.ResidentId id = resident(SOURCE, 10);
        FakeResident first = new FakeResident();
        FakeResident second = new FakeResident();
        FakeResident third = new FakeResident();
        scheduler.putPublishedResident(id, first, false);
        scheduler.putPublishedResident(id, second, true);

        assertEquals(List.of(second, first), scheduler.putPublishedResident(id, third, false));
        assertNull(scheduler.publishedSlot(id).previous);
        assertSame(third, scheduler.publishedSlot(id).current);
    }

    @Test
    void clearSourceIsIsolatedAndDestroyReleasesRemainingResidentsOnce() {
        ResourceId otherSource = ResourceId.of("test", "other");
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        FakeResident sourceCurrent = new FakeResident();
        FakeResident sourcePrevious = new FakeResident();
        FakeResident other = new FakeResident();
        RtSceneGeometryManager.ResidentId sourceId = resident(SOURCE, 10);
        RtSceneGeometryManager.ResidentId otherId = resident(otherSource, 10);
        scheduler.putPublishedResident(sourceId, sourcePrevious, false);
        scheduler.putPublishedResident(sourceId, sourceCurrent, true);
        scheduler.putPublishedResident(otherId, other, false);
        scheduler.putPublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), identity(), 0xff));
        scheduler.putPublishedPlacement(instance(otherSource, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), identity(), 0xff));

        List<RtSceneGeometryManager.GroupResident> cleared = scheduler.clearSource(SOURCE);

        assertTrue(cleared.contains(sourceCurrent));
        assertTrue(cleared.contains(sourcePrevious));
        assertNull(scheduler.publishedSlot(sourceId));
        assertTrue(scheduler.publishedSlot(otherId) != null);
        scheduler.assertPublishedIndexConsistent();

        scheduler.destroyAfterDeviceIdle(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),
                (prepared, destroyed) -> { });
        assertEquals(1, other.destroyCount);
        assertEquals(0, sourceCurrent.destroyCount);
        assertEquals(0, sourcePrevious.destroyCount);
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
    void clearingSourceKeepsRunningGroupKeyReservedUntilTerminal() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        RtSceneGeometryManager.PreparedGroup running = scheduler.startable().getFirst().prepared();

        scheduler.clearSource(SOURCE);
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(20, payload())));

        assertEquals(List.of(), scheduler.startable());
        scheduler.complete(running.barrier, false);
        assertEquals(FIRST, scheduler.startable().getFirst().key());
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

    private static RtSceneGeometryManager.GroupScheduler publishedPair() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.putPublishedResident(resident(SOURCE, 10), new FakeResident(), false);
        scheduler.putPublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), identity(), 0xff));
        return scheduler;
    }

    private static RtSceneGeometryManager.ResidentId resident(ResourceId source, long key) {
        return new RtSceneGeometryManager.ResidentId(source, SceneGeometryKey.of(key));
    }

    private static RtSceneGeometryManager.InstanceId instance(ResourceId source, long key) {
        return new RtSceneGeometryManager.InstanceId(source, SceneGeometryKey.of(key));
    }

    private static final class FakeResident implements RtSceneGeometryManager.GroupResident {
        private final dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse graphicsUse =
                new dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse();
        int destroyCount;

        @Override
        public dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse graphicsUse() {
            return graphicsUse;
        }

        @Override
        public void append(RtSceneGeometryManager.FrameUpdate update, float[] transform, int mask,
                           RtSceneGeometryManager.InstanceKey key, RtSceneGeometryManager.GroupResident previous,
                           boolean resetTransformMotion) {
        }

        @Override
        public void destroy() {
            destroyCount++;
        }
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    }
}
