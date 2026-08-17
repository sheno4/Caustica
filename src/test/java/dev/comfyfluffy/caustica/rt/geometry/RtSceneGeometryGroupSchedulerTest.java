package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
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
    private static final RtSceneGeometryManager.GroupKey TRANSFORM = new RtSceneGeometryManager.GroupKey(
            SOURCE, new SceneGeometryKey(4L, 1L));
    private static final RtSceneGeometryManager.GroupKey SECOND_TRANSFORM = new RtSceneGeometryManager.GroupKey(
            SOURCE, new SceneGeometryKey(4L, 2L));
    private static final RtSceneGeometryManager.GroupKey LIFECYCLE = new RtSceneGeometryManager.GroupKey(
            SOURCE, new SceneGeometryKey(5L, 1L));

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
    void framePublicationGateDoesNotThrottleLoadingProgressBeforeACompletedFrame() {
        RtSceneGeometryManager.FramePublicationGate gate = new RtSceneGeometryManager.FramePublicationGate();
        RtSceneGeometryManager.ResidentId id = resident(SOURCE, 10);

        assertTrue(!gate.blocks(id));
        gate.published(id);
        assertTrue(!gate.blocks(id));
        gate.published(id);
        assertTrue(!gate.blocks(id));
    }

    @Test
    void framePublicationGateAllowsOneReplacementPerResidentAndIndependentResidents() {
        RtSceneGeometryManager.FramePublicationGate gate = new RtSceneGeometryManager.FramePublicationGate();
        RtSceneGeometryManager.ResidentId first = resident(SOURCE, 10);
        RtSceneGeometryManager.ResidentId second = resident(SOURCE, 20);
        gate.frameCompleted();

        assertTrue(!gate.blocks(first));
        gate.published(first);
        assertTrue(gate.blocks(first));
        assertTrue(!gate.blocks(second));

        gate.frameCompleted();
        assertTrue(!gate.blocks(first));
    }

    @Test
    void sourceClearReleasesOnlyItsResidentPublicationMarks() {
        ResourceId otherSource = ResourceId.of("test", "other");
        RtSceneGeometryManager.FramePublicationGate gate = new RtSceneGeometryManager.FramePublicationGate();
        RtSceneGeometryManager.ResidentId first = resident(SOURCE, 10);
        RtSceneGeometryManager.ResidentId second = resident(otherSource, 10);
        gate.frameCompleted();
        gate.published(first);
        gate.published(second);

        gate.clearSource(SOURCE);

        assertTrue(!gate.blocks(first));
        assertTrue(gate.blocks(second));
    }

    @Test
    void clearingPublicationGateRestoresLoadingModeUntilAnotherFrameCompletes() {
        RtSceneGeometryManager.FramePublicationGate gate = new RtSceneGeometryManager.FramePublicationGate();
        RtSceneGeometryManager.ResidentId id = resident(SOURCE, 10);
        gate.frameCompleted();
        gate.published(id);
        assertTrue(gate.blocks(id));

        gate.clear();
        gate.published(id);
        assertTrue(!gate.blocks(id));

        gate.frameCompleted();
        gate.published(id);
        assertTrue(gate.blocks(id));
    }

    @Test
    void failedCancelledAndNonRunningTerminalsBypassResidentDeferral() {
        RuntimeException failure = new RuntimeException("failed");

        assertTrue(!RtSceneGeometryManager.deferSuccessfulTerminal(true, false, failure, true));
        assertTrue(!RtSceneGeometryManager.deferSuccessfulTerminal(true, true, null, true));
        assertTrue(!RtSceneGeometryManager.deferSuccessfulTerminal(false, false, null, true));
        assertTrue(RtSceneGeometryManager.deferSuccessfulTerminal(true, false, null, true));
    }

    @Test
    void residentGateKeepsHistoryAtTheLastSnapshottedReplacement() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.FramePublicationGate gate = new RtSceneGeometryManager.FramePublicationGate();
        RtSceneGeometryManager.ResidentId id = resident(SOURCE, 10);
        FakeResident original = new FakeResident();
        FakeResident first = new FakeResident();
        FakeResident second = new FakeResident();
        scheduler.putPublishedResident(id, original, false);
        gate.frameCompleted();

        assertTrue(!gate.blocks(id));
        scheduler.putPublishedResident(id, first, true);
        gate.published(id);

        assertTrue(gate.blocks(id));
        assertSame(first, scheduler.publishedSlot(id).current);
        assertSame(original, scheduler.publishedSlot(id).previous);

        scheduler.drainPreviousResidents(retired -> assertSame(original, retired));
        gate.frameCompleted();
        assertTrue(!gate.blocks(id));
        scheduler.putPublishedResident(id, second, true);
        assertSame(second, scheduler.publishedSlot(id).current);
        assertSame(first, scheduler.publishedSlot(id).previous);
    }

    @Test
    void staleRevisionCannotReplaceANewerAcceptedGroup() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Drop(10)));
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Drop(20)));

        assertEquals(2L, scheduler.startable().getFirst().prepared().revision);
    }

    @Test
    void staleAndRejectedRevisionsDoNotIncrementAcceptedTelemetry() {
        boolean previous = CausticaConfig.Rt.FrameStats.ENABLED.value();
        CausticaConfig.Rt.FrameStats.ENABLED.set(true);
        RtFrameStats.FRAME.begin();
        try {
            RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
            scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Put(10, payload())));

            assertEquals(1L, RtFrameStats.FRAME.counterValue("geometryGroupsAccepted"));
            assertEquals(1L, RtFrameStats.FRAME.counterValue("geometryPutsAccepted"));

            scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Put(20, payload())));
            assertThrows(IllegalArgumentException.class, () -> scheduler.submit(
                    group(SECOND, new RtSceneGeometryManager.Place(3, 30, identity(), 0xff))));

            assertEquals(1L, RtFrameStats.FRAME.counterValue("geometryGroupsAccepted"));
            assertEquals(1L, RtFrameStats.FRAME.counterValue("geometryPutsAccepted"));
        } finally {
            CausticaConfig.Rt.FrameStats.ENABLED.set(false);
            RtFrameStats.FRAME.end();
            CausticaConfig.Rt.FrameStats.ENABLED.set(previous);
        }
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
    void transformFastLaneStartsWhileResidentReplacementIsRunning() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        assertEquals(FIRST, scheduler.startable().getFirst().key());
        scheduler.submit(group(SECOND, updatePlacement(3, 24f)));

        assertEquals(SECOND, scheduler.startable().getFirst().key());
    }

    @Test
    void transformCannotReferenceAnUnpublishedPlacement() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(group(FIRST, updatePlacement(3, 24f))));
    }

    @Test
    void transformQueuesBehindAnAcceptedInitialPlacementAndKeepsTheNewestRevision() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        RtSceneGeometryManager.PreparedGroup initial = scheduler.startable().getFirst().prepared();

        scheduler.submit(group(TRANSFORM, 1L, updatePlacement(3, 12f)));
        scheduler.submit(group(TRANSFORM, 2L, updatePlacement(3, 24f)));
        assertEquals(List.of(), scheduler.startable());

        scheduler.putPublishedResident(resident(SOURCE, 10), new FakeResident(), false);
        scheduler.putPublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), identity(), 0xff));
        scheduler.complete(initial.barrier, true);

        RtSceneGeometryManager.PreparedGroup transform = scheduler.startable().getFirst().prepared();
        assertEquals(24f, transform.diff.placementUpdates.get(SceneGeometryKey.of(3)).transform[3]);
    }

    @Test
    void failedInitialPlacementDiscardsItsDependentTransform() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        RtSceneGeometryManager.PreparedGroup initial = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(TRANSFORM, updatePlacement(3, 24f)));

        scheduler.complete(initial.barrier, false);

        assertEquals(List.of(), scheduler.startable());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void oneBatchCanTransformAnEarlierPromisedPlacement() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();

        scheduler.submitAll(List.of(
                group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                        new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)),
                group(TRANSFORM, updatePlacement(3, 24f))), null, null);

        assertEquals(List.of(FIRST), scheduler.startable().stream().map(RtSceneGeometryManager.GroupRun::key).toList());
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void rejectedBatchDoesNotLeakAPlacementPromise() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();

        assertThrows(IllegalArgumentException.class, () -> scheduler.submitAll(List.of(
                group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                        new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)),
                group(SECOND, new RtSceneGeometryManager.Place(4, 20, identity(), 0xff))), null, null));

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(group(TRANSFORM, updatePlacement(3, 24f))));
        assertEquals(List.of(), scheduler.startable());
    }

    @Test
    void clearingSourceRemovesRunningAndPendingPlacementPromises() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        scheduler.startable();
        scheduler.submit(group(TRANSFORM, updatePlacement(3, 24f)));

        scheduler.clearSource(SOURCE);

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(group(TRANSFORM, 2L, updatePlacement(3, 32f))));
    }

    @Test
    void transformSubmittedAfterAcceptedRemovalIsRejected() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Remove(3)));

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(group(SECOND, updatePlacement(3, 24f))));
    }

    @Test
    void transformMergedAfterPlaceKeepsTheAtomicTargetAndNewestTransform() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.putPublishedResident(resident(SOURCE, 10), new FakeResident(), false);
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Place(3, 10, identity(), 0xff)));
        scheduler.submit(group(FIRST, 2L, updatePlacement(3, 24f)));

        RtSceneGeometryManager.GroupDiff diff = scheduler.startable().getFirst().prepared().diff;
        assertEquals(24f, diff.placements.get(SceneGeometryKey.of(3)).transform[3]);
        assertTrue(diff.placementUpdates.isEmpty());
    }

    @Test
    void transformRemoveAndPlaceUseSourceOperationOrder() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        scheduler.submit(group(FIRST,
                updatePlacement(3, 8f),
                new RtSceneGeometryManager.Remove(3),
                new RtSceneGeometryManager.Place(3, 10, translation(16f), 0x3f),
                updatePlacement(3, 24f)));

        RtSceneGeometryManager.GroupDiff diff = scheduler.startable().getFirst().prepared().diff;
        assertEquals(24f, diff.placements.get(SceneGeometryKey.of(3)).transform[3]);
        assertEquals(0xff, diff.placements.get(SceneGeometryKey.of(3)).mask);
        assertTrue(diff.placementUpdates.isEmpty());
        assertTrue(diff.removes.isEmpty());
    }

    @Test
    void lateResidentTerminalCannotRollBackFastTransform() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager.ResidentId resident = resident(SOURCE, 10);
        RtSceneGeometryManager.InstanceId instance = instance(SOURCE, 3);
        RtSceneGeometryManager.PublishedPlacement published = scheduler.publishedPlacement(instance);
        FakeResident replacement = new FakeResident();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload())));
        RtSceneGeometryManager.PreparedGroup mesh = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(SECOND, updatePlacement(3, 24f)));
        RtSceneGeometryManager.PreparedGroup transform = scheduler.startable().getFirst().prepared();

        scheduler.updatePublishedPlacement(instance,
                new RtSceneGeometryManager.PlacementUpdate(translation(24f), 0xff, SceneOrigin.ZERO));
        scheduler.complete(transform.barrier, true);
        scheduler.putPublishedResident(resident, replacement, true);
        scheduler.complete(mesh.barrier, true);

        assertSame(published, scheduler.publishedPlacement(instance));
        assertEquals(24f, scheduler.publishedPlacement(instance).placement.transform[3]);
        assertSame(replacement, scheduler.publishedPlacement(instance).resident.current);
    }

    @Test
    void rapidIdReuseOrdersOldTransformThenLifecycleRemovalThenNewInitial() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        scheduler.submit(group(TRANSFORM, updatePlacement(3, 12f)));
        RtSceneGeometryManager.PreparedGroup oldTransform = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(LIFECYCLE,
                new RtSceneGeometryManager.Remove(3), new RtSceneGeometryManager.Drop(10)));
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, translation(24f), 0xff)));

        assertEquals(List.of(), scheduler.startable());
        scheduler.complete(oldTransform.barrier, true);
        RtSceneGeometryManager.PreparedGroup removal = scheduler.startable().getFirst().prepared();
        assertEquals(LIFECYCLE, removal.key);
        assertEquals(List.of(), scheduler.startable());

        scheduler.removePublishedPlacement(instance(SOURCE, 3));
        scheduler.removePublishedResident(resident(SOURCE, 10));
        scheduler.complete(removal.barrier, true);
        assertEquals(FIRST, scheduler.startable().getFirst().key());
    }

    @Test
    void pendingLifecycleRemovalCannotMergeWithReusedIdInitialGroup() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        scheduler.submit(group(LIFECYCLE,
                new RtSceneGeometryManager.Remove(3), new RtSceneGeometryManager.Drop(10)));
        RtSceneGeometryManager.PreparedGroup removal = scheduler.startable().getFirst().prepared();
        scheduler.submit(group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                new RtSceneGeometryManager.Place(3, 10, translation(24f), 0xff)));

        assertEquals(List.of(), scheduler.startable());
        assertTrue(removal.diff.removes.contains(SceneGeometryKey.of(3)));
        assertTrue(removal.diff.drops.contains(SceneGeometryKey.of(10)));
        scheduler.removePublishedPlacement(instance(SOURCE, 3));
        scheduler.removePublishedResident(resident(SOURCE, 10));
        scheduler.complete(removal.barrier, true);
        assertEquals(FIRST, scheduler.startable().getFirst().key());
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
        assertEquals(1, scheduler.previousResidentSlotCount());
        assertSame(second, scheduler.publishedSlot(id).previous);
        assertSame(third, scheduler.publishedSlot(id).current);

        AtomicReference<RtSceneGeometryManager.GroupResident> drained = new AtomicReference<>();
        scheduler.drainPreviousResidents(drained::set);
        assertSame(second, drained.get());
        assertNull(scheduler.publishedSlot(id).previous);
        assertEquals(0, scheduler.previousResidentSlotCount());
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
        assertEquals(0, scheduler.previousResidentSlotCount());
    }

    @Test
    void droppingResidentRemovesItsPendingPreviousSlot() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        RtSceneGeometryManager.ResidentId id = resident(SOURCE, 10);
        FakeResident first = new FakeResident();
        FakeResident second = new FakeResident();
        scheduler.putPublishedResident(id, first, false);
        scheduler.putPublishedResident(id, second, true);

        assertEquals(List.of(second, first), scheduler.removePublishedResident(id));
        assertEquals(0, scheduler.previousResidentSlotCount());
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
        assertEquals(0, scheduler.previousResidentSlotCount());
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
    void malformedPlacementTransformsAreRejectedBeforeSchedulerAdmission() {
        RtSceneGeometryManager.GroupScheduler initial = new RtSceneGeometryManager.GroupScheduler();
        assertThrows(IllegalArgumentException.class, () -> initial.submit(group(FIRST,
                new RtSceneGeometryManager.Put(2, payload()),
                new RtSceneGeometryManager.Place(3, 2, new float[11], 0xff))));
        initial.submit(group(FIRST, new RtSceneGeometryManager.Put(2, payload()),
                new RtSceneGeometryManager.Place(3, 2, identity(), 0xff)));
        assertEquals(1, initial.startable().size());

        RtSceneGeometryManager.GroupScheduler update = publishedPair();
        assertThrows(IllegalArgumentException.class, () -> update.submit(group(TRANSFORM,
                new RtSceneGeometryManager.UpdatePlacement(3, new float[13], 0xff))));
        update.submit(group(TRANSFORM, updatePlacement(3, 12f)));
        assertEquals(1, update.startable().size());
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
    void rejectedBatchDoesNotCommitEarlierPutToDropCoalescing() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Put(10, payload())));

        assertThrows(IllegalArgumentException.class, () -> scheduler.submitAll(List.of(
                group(FIRST, 2L, new RtSceneGeometryManager.Drop(10)),
                group(SECOND, new RtSceneGeometryManager.Place(3, 20, identity(), 0xff))), null, null));

        RtSceneGeometryManager.PreparedGroup prepared = scheduler.startable().getFirst().prepared();
        assertTrue(prepared.diff.puts.containsKey(SceneGeometryKey.of(10)));
        assertTrue(prepared.diff.drops.isEmpty());
    }

    @Test
    void acceptedPutToDropRevisionRemovesTheQueuedPut() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.submit(group(FIRST, 1L, new RtSceneGeometryManager.Put(10, payload())));
        scheduler.submit(group(FIRST, 2L, new RtSceneGeometryManager.Drop(10)));

        RtSceneGeometryManager.PreparedGroup prepared = scheduler.startable().getFirst().prepared();
        assertTrue(prepared.diff.puts.isEmpty());
        assertTrue(prepared.diff.drops.contains(SceneGeometryKey.of(10)));
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
    void frameLateDrainProcessesAPutPreparationFailureWithoutPublication() {
        RuntimeException cause = new RuntimeException("material packing failed");
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> {
            throw cause;
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<RtSceneGeometryManager.PublicationAck> published = new AtomicReference<>();
        manager.submit(List.of(group(FIRST, new RtSceneGeometryManager.Put(10, payload()))),
                published::set, failure::set);

        manager.publishReadyForFrame(null);

        assertSame(cause, failure.get());
        assertNull(published.get());
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
    void transformSubmittedBeforeBeginUpdatePublishesInItsFramePreparationPass() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> null, scheduler);
        manager.completeFramePublicationBoundary();
        List<Long> published = new java.util.ArrayList<>();
        manager.submit(List.of(group(TRANSFORM, updatePlacement(3, 24f))),
                acknowledgment -> published.add(acknowledgment.revision()));

        manager.publishReadyForFrame(null);
        manager.publishReadyForFrame(null);

        assertEquals(24f, scheduler.publishedPlacement(instance(SOURCE, 3)).placement.transform[3]);
        assertEquals(List.of(1L), published);
    }

    @Test
    void promisedTransformJoinsInitialPlacementBeforeTheNextFrameSnapshot() {
        RtSceneGeometryManager.GroupScheduler scheduler = new RtSceneGeometryManager.GroupScheduler();
        scheduler.putPublishedResident(resident(SOURCE, 10), new FakeResident(), false);
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> null, scheduler);
        List<RtSceneGeometryManager.GroupKey> published = new java.util.ArrayList<>();
        manager.submit(List.of(group(FIRST,
                        new RtSceneGeometryManager.Place(3, 10, identity(), 0xff))),
                acknowledgment -> published.add(acknowledgment.key()));
        manager.progress(null);
        manager.submit(List.of(group(TRANSFORM, updatePlacement(3, 24f))),
                acknowledgment -> published.add(acknowledgment.key()));

        manager.publishReadyForFrame(null);

        assertEquals(24f, scheduler.publishedPlacement(instance(SOURCE, 3)).placement.transform[3]);
        assertEquals(List.of(FIRST, TRANSFORM), published);
    }

    @Test
    void framePreparationPublishesOlderReadyAndNewerPendingTransformsInOrder() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> null, scheduler);
        List<Long> published = new java.util.ArrayList<>();
        manager.submit(List.of(group(TRANSFORM, 1L, updatePlacement(3, 12f))),
                acknowledgment -> published.add(acknowledgment.revision()));
        manager.progress(null);
        manager.submit(List.of(group(TRANSFORM, 2L, updatePlacement(3, 24f))),
                acknowledgment -> published.add(acknowledgment.revision()));

        manager.publishReadyForFrame(null);
        manager.progress(null);

        assertEquals(24f, scheduler.publishedPlacement(instance(SOURCE, 3)).placement.transform[3]);
        assertEquals(List.of(1L, 2L), published);
    }

    @Test
    void framePreparationDoesNotStartReplacementUnblockedByLifecycleRemoval() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> null, scheduler);
        manager.completeFramePublicationBoundary();
        List<RtSceneGeometryManager.GroupKey> published = new java.util.ArrayList<>();
        manager.submit(List.of(
                group(LIFECYCLE, new RtSceneGeometryManager.Remove(3)),
                group(FIRST, new RtSceneGeometryManager.Put(10, payload()),
                        new RtSceneGeometryManager.Place(3, 10, translation(24f), 0xff))),
                acknowledgment -> published.add(acknowledgment.key()));

        manager.publishReadyForFrame(null);

        assertEquals(List.of(LIFECYCLE), published);
        assertNull(scheduler.publishedPlacement(instance(SOURCE, 3)));
        assertEquals(1, scheduler.pendingCount());
        assertEquals(0, scheduler.runningCount());
    }

    @Test
    void framePreparationPublishesIndependentTransformsTogether() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        scheduler.putPublishedResident(resident(SOURCE, 20), new FakeResident(), false);
        scheduler.putPublishedPlacement(instance(SOURCE, 4),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(20), identity(), 0xff));
        RtSceneGeometryManager manager = new RtSceneGeometryManager((material, coverage) -> null, scheduler);
        List<RtSceneGeometryManager.GroupKey> published = new java.util.ArrayList<>();
        manager.submit(List.of(
                group(TRANSFORM, updatePlacement(3, 12f)),
                group(SECOND_TRANSFORM, updatePlacement(4, 24f))),
                acknowledgment -> published.add(acknowledgment.key()));

        manager.publishReadyForFrame(null);

        assertEquals(12f, scheduler.publishedPlacement(instance(SOURCE, 3)).placement.transform[3]);
        assertEquals(24f, scheduler.publishedPlacement(instance(SOURCE, 4)).placement.transform[3]);
        assertEquals(List.of(TRANSFORM, SECOND_TRANSFORM), published);
    }

    @Test
    void placementHistoryAdvancesOnlyAtFrameSnapshot() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager.PublishedPlacement published = scheduler.publishedPlacement(instance(SOURCE, 3));
        RtSceneGeometryManager.Placement original = published.placement;
        assertNull(published.previousPlacement);
        assertSame(original, published.historyPlacement());
        published.completeFrameSnapshot();

        scheduler.updatePublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.PlacementUpdate(translation(12f), 0xff, SceneOrigin.ZERO));
        scheduler.updatePublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.PlacementUpdate(translation(24f), 0xff, SceneOrigin.ZERO));

        assertSame(original, published.historyPlacement());
        assertEquals(24f, published.placement.transform[3]);
        published.completeFrameSnapshot();
        assertSame(published.placement, published.historyPlacement());
    }

    @Test
    void placementRetargetPreservesLastFrameHistory() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager.PublishedPlacement published = scheduler.publishedPlacement(instance(SOURCE, 3));
        published.completeFrameSnapshot();
        RtSceneGeometryManager.Placement previous = published.placement;
        scheduler.putPublishedResident(resident(SOURCE, 20), new FakeResident(), false);

        scheduler.putPublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(20), translation(12f), 0xff));

        assertSame(published, scheduler.publishedPlacement(instance(SOURCE, 3)));
        assertSame(previous, published.historyPlacement());
        assertEquals(resident(SOURCE, 20), published.resident.id);
    }

    @Test
    void placementRemovalAndSourceClearResetSnapshotHistory() {
        RtSceneGeometryManager.GroupScheduler scheduler = publishedPair();
        RtSceneGeometryManager.PublishedPlacement removed = scheduler.publishedPlacement(instance(SOURCE, 3));
        removed.completeFrameSnapshot();
        scheduler.removePublishedPlacement(instance(SOURCE, 3));
        scheduler.putPublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), translation(12f), 0xff));
        RtSceneGeometryManager.PublishedPlacement replaced = scheduler.publishedPlacement(instance(SOURCE, 3));
        assertNull(replaced.previousPlacement);
        assertSame(replaced.placement, replaced.historyPlacement());

        scheduler.clearSource(SOURCE);
        scheduler.putPublishedResident(resident(SOURCE, 10), new FakeResident(), false);
        scheduler.putPublishedPlacement(instance(SOURCE, 3),
                new RtSceneGeometryManager.Placement(SceneGeometryKey.of(10), translation(24f), 0xff));
        RtSceneGeometryManager.PublishedPlacement afterClear = scheduler.publishedPlacement(instance(SOURCE, 3));
        assertNull(afterClear.previousPlacement);
        assertSame(afterClear.placement, afterClear.historyPlacement());
    }

    @Test
    void tableSlotReusesAndResetsItsFrameStaging() {
        RtSceneGeometryManager.TableSlot table = new RtSceneGeometryManager.TableSlot(null, null);
        table.beginFrame(4);
        var instances = table.instances;
        var persistentUses = table.persistentUses;
        var historyUses = table.historyUses;
        var historyRetire = table.historyRetire;
        FakeResident resident = new FakeResident();
        instances.append(translation(1f), 0f, 0f, 0f, 10L, 0, 0xff, 0);
        persistentUses.add(resident);
        historyUses.add(resident);
        historyRetire.add(resident);

        table.beginFrame(2);

        assertSame(instances, table.instances);
        assertSame(persistentUses, table.persistentUses);
        assertSame(historyUses, table.historyUses);
        assertSame(historyRetire, table.historyRetire);
        assertEquals(0, table.instances.size());
        assertTrue(table.persistentUses.isEmpty());
        assertTrue(table.historyUses.isEmpty());
        assertTrue(table.historyRetire.isEmpty());
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

    private static RtSceneGeometryManager.UpdatePlacement updatePlacement(long instance, float x) {
        return new RtSceneGeometryManager.UpdatePlacement(instance, translation(x), 0xff);
    }

    private static float[] translation(float x) {
        float[] transform = identity();
        transform[3] = x;
        return transform;
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
        public void append(RtSceneGeometryManager.FrameUpdate update, RtSceneGeometryManager.Placement placement,
                           RtSceneGeometryManager.PublishedPlacement published,
                           RtSceneGeometryManager.GroupResident previous) {
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
