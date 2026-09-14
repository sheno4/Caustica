package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainDispatchPlannerTest {
    @Test void columnRankingPreservesFullSectionOrderAcrossHorizontalTiesAndVerticalMovement() {
        var fixture = new Fixture();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            fixture.planner.column(RtTerrain.columnKey(x, z), true);
        }
        var keys = new ArrayList<Long>();
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -4; y < 20; y++) {
            keys.add(RtTerrain.sectionKey(x, y, z));
        }
        java.util.Collections.shuffle(keys, new Random(47));
        keys.forEach(fixture.updates::want);
        var replaced = keys.subList(0, 90);
        for (long key : replaced) fixture.updates.complete(fixture.updates.sections.get(key).request, "visible");
        fixture.updates.published(fixture.updates.ready());
        fixture.updates.dirty(replaced);
        var random = new Random(23);
        for (int pass = 0; pass < 12; pass++) {
            for (int removed = 0; removed < 10; removed++) fixture.updates.remove(keys.removeFirst());
            int x = pass < 6 ? 0 : random.nextInt(11) - 5;
            int y = random.nextInt(24) - 4, z = pass < 6 ? 0 : random.nextInt(11) - 5;
            var expected = keys.stream().sorted(java.util.Comparator
                    .comparingInt((Long key) -> fixture.updates.sections.get(key).ready ? 0 : 1)
                    .thenComparingLong(key -> RtTerrain.distance(key, x, y, z))
                    .thenComparingLong(Long::longValue)).limit(128).toList();
            fixture.planner.request(new TerrainDispatchPlanner.Context(fixture.epoch, x, y, z, -4, 19, 128), true);
            fixture.run();
            assertEquals(expected, fixture.planner.poll().candidates().stream().map(TerrainDispatchPlanner.Candidate::key).toList());
        }
    }

    @Test void pendingDeltasRetainPrioritiesAcrossCameraChangesAndVisibleReplacement() {
        var fixture = new Fixture();
        fixture.load(-1, 9);
        fixture.want(8);
        fixture.want(4);
        fixture.want(0);
        assertEquals(List.of(0, 4), fixture.select(0));
        assertEquals(List.of(8, 4), fixture.select(8));
        var old = fixture.request(4);
        fixture.updates.complete(old, "visible");
        fixture.updates.published(fixture.updates.ready());
        fixture.updates.dirty(List.of(key(4)));
        assertEquals(List.of(4, 0), fixture.select(0));
    }

    @Test void randomizedInsertionAndRemovalsSelectTheNearestAvailableCurrentRequests() {
        var random = new Random(42);
        for (int pass = 0; pass < 10; pass++) {
            var fixture = new Fixture();
            fixture.load(-1, 100);
            var keys = new ArrayList<Integer>();
            for (int x = 0; x < 100; x++) keys.add(x);
            java.util.Collections.shuffle(keys, random);
            keys.forEach(fixture::want);
            for (int x = 0; x < 100; x += 5) fixture.updates.remove(key(x));
            int center = random.nextInt(100);
            var expected = keys.stream().filter(x -> x % 5 != 0)
                    .sorted(java.util.Comparator.comparingInt((Integer x) -> Math.abs(x - center))
                            .thenComparingInt(x -> x)).limit(2).toList();
            assertEquals(expected, fixture.select(center));
        }
    }

    @Test void coalescingKeepsOnlyTheLatestRequestAndOldPlansCannotDispatchIt() {
        var fixture = new Fixture();
        fixture.load(-1, 1);
        fixture.want(0);
        fixture.requestPlan(0);
        fixture.run();
        var oldPlan = fixture.planner.poll();
        var old = oldPlan.candidates().getFirst().request();
        fixture.updates.remove(key(0));
        fixture.want(0);
        fixture.updates.dirty(List.of(key(0)));
        var latest = fixture.request(0);
        assertFalse(old.awaitingExtraction());
        fixture.requestPlan(0);
        fixture.run();
        var currentPlan = fixture.planner.poll();
        assertEquals(1, currentPlan.candidates().size());
        assertSame(latest, currentPlan.candidates().getFirst().request());
        fixture.updates.dispatched(latest);
        assertFalse(latest.awaitingExtraction());
        fixture.updates.retry(latest);
        assertTrue(latest.awaitingExtraction());
        assertEquals(List.of(0), fixture.select(0));
    }

    @Test void haloAvailabilityChangesUnblockCandidatesWithoutStarvingOtherColumns() {
        var fixture = new Fixture();
        fixture.load(-1, 5);
        fixture.want(0);
        fixture.want(4);
        fixture.planner.column(RtTerrain.columnKey(-1, 0), false);
        assertEquals(List.of(4), fixture.select(0));
        fixture.planner.column(RtTerrain.columnKey(-1, 0), true);
        assertEquals(List.of(0, 4), fixture.select(0));
        fixture.planner.column(RtTerrain.columnKey(0, 1), false);
        assertEquals(List.of(4), fixture.select(0));
    }

    @Test void replacedPartiallyConsumedPlansDoNotReserveOrLoseCandidates() {
        var fixture = new Fixture();
        fixture.load(-1, 9);
        fixture.want(0);
        fixture.want(4);
        fixture.want(8);
        fixture.requestPlan(0);
        fixture.run();
        var first = fixture.planner.poll();
        fixture.updates.dispatched(first.candidates().getFirst().request());
        assertEquals(List.of(8, 4), fixture.select(8));
        assertTrue(first.candidates().get(1).request().awaitingExtraction());
    }

    @Test void pollingACompletedBatchDoesNotWaitForQueuedPlanning() throws Exception {
        var fixture = new Fixture();
        fixture.load(-1, 9);
        fixture.want(0);
        fixture.want(8);
        fixture.requestPlan(0);
        fixture.run();
        fixture.requestPlan(8);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var next = executor.submit(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException problem) { throw new AssertionError(problem); }
                fixture.run();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var completed = fixture.planner.poll();
                assertEquals(0, RtTerrain.sectionX(completed.candidates().getFirst().key()));
                assertNull(fixture.planner.poll());
                assertFalse(next.isDone());
            } finally { release.countDown(); }
            next.get(5, TimeUnit.SECONDS);
            assertEquals(8, RtTerrain.sectionX(fixture.planner.poll().candidates().getFirst().key()));
        }
    }

    @Test void latestCameraDemandCoalescesBeforeTheWorkerStarts() {
        var fixture = new Fixture();
        fixture.load(-1, 9);
        fixture.want(0);
        fixture.want(8);
        fixture.requestPlan(0);
        fixture.requestPlan(4);
        fixture.requestPlan(8);
        assertEquals(1, fixture.jobs.size());
        fixture.run();
        assertEquals(8, RtTerrain.sectionX(fixture.planner.poll().candidates().getFirst().key()));
    }

    @Test void resetAndQueuedCancellationPermitANewEpochWithoutOldCandidates() {
        var fixture = new Fixture();
        fixture.load(-1, 9);
        fixture.want(0);
        fixture.requestPlan(0);
        fixture.updates.clear();
        fixture.planner.reset();
        fixture.jobs.removeFirst().cancelled.run();
        fixture.load(-1, 9);
        fixture.want(8);
        fixture.epoch++;
        assertEquals(List.of(8), fixture.select(0));
        assertNull(fixture.planner.poll());
    }

    @Test void emptyUnchangedSelectionDoesNotRescheduleEachFrame() {
        var fixture = new Fixture();
        assertTrue(fixture.select(0).isEmpty());
        fixture.requestPlan(0);
        assertTrue(fixture.jobs.isEmpty());
    }

    private static long key(int x) { return RtTerrain.sectionKey(x, 0, 0); }

    private static final class Fixture {
        private record Job(Runnable work, Runnable cancelled) { }
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        final TerrainDispatchPlanner<String> planner = new TerrainDispatchPlanner<>(
                (job, cancelled) -> jobs.add(new Job(job, cancelled)), problem -> { throw new AssertionError(problem); });
        final TerrainUpdates<String> updates = new TerrainUpdates<>(value -> { }, planner::pending);
        long epoch = 1;

        void want(int x) { updates.want(key(x)); }
        TerrainUpdates.Request<String> request(int x) { return updates.sections.get(key(x)).request; }
        void load(int min, int max) {
            for (int x = min; x <= max; x++) for (int z = -1; z <= 1; z++) {
                planner.column(RtTerrain.columnKey(x, z), true);
            }
        }
        void requestPlan(int x) { planner.request(new TerrainDispatchPlanner.Context(epoch, x, 0, 0, 0, 0, 2), false); }
        void run() { jobs.removeFirst().work.run(); }
        List<Integer> select(int x) {
            requestPlan(x);
            run();
            var plan = planner.poll();
            assertEquals(epoch, plan.epoch());
            return plan.candidates().stream().map(candidate -> RtTerrain.sectionX(candidate.key())).toList();
        }
    }
}
