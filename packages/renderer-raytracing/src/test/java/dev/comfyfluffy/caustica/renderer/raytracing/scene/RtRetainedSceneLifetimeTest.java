package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtRetainedSceneLifetimeTest {
    @Test
    void frameCapturesEveryScenesEffectiveValuesBeforeAnySceneRead() {
        Object firstScene = new Object();
        Object secondScene = new Object();
        Map<Object, List<String>> root = new IdentityHashMap<>();
        root.put(firstScene, List.of("first instance"));
        root.put(secondScene, List.of("second instance"));
        AtomicInteger latestTransformEpoch = new AtomicInteger(1);

        Map<Object, List<Integer>> frame = RtRetainedSceneBackend.captureFrameValues(
                root, ignored -> latestTransformEpoch.get());
        assertEquals(1, frame.get(firstScene).getFirst());

        latestTransformEpoch.set(2);

        assertEquals(1, frame.get(secondScene).getFirst());
    }

    @Test
    void graphicsUseLatchesGeometryAndContentFromOneRevision() {
        record Root(int geometryRevision, int contentRevision) { }
        Object firstUse = new Object();
        Object secondUse = new Object();
        Map<Object, Root> frames = new LinkedHashMap<>();
        AtomicReference<Root> published = new AtomicReference<>(new Root(1, 1));

        Root firstGeometryRead = RtRetainedSceneBackend.latchFrameRoot(
                frames, firstUse, published::get);
        published.set(new Root(2, 2));
        Root firstContentRead = RtRetainedSceneBackend.latchFrameRoot(
                frames, firstUse, published::get);
        Root secondFrame = RtRetainedSceneBackend.latchFrameRoot(
                frames, secondUse, published::get);

        assertSame(firstGeometryRead, firstContentRead);
        assertEquals(firstGeometryRead.geometryRevision(), firstContentRead.contentRevision());
        assertEquals(2, secondFrame.geometryRevision());
        assertEquals(2, secondFrame.contentRevision());
    }

    @Test
    void sessionCloseSettlesGpuThenReleasesRootsBeforeContributionDrain() {
        List<String> events = new ArrayList<>();
        Map<Object, AutoCloseable> frames = new LinkedHashMap<>();
        Map<Object, AutoCloseable> histories = new LinkedHashMap<>();
        frames.put(new Object(), () -> events.add("frame"));
        histories.put(new Object(), () -> events.add("history"));

        RtRetainedSceneBackend.settleAndReleaseTerminalFrameRoots(
                () -> events.add("idle"), frames, histories);
        events.add("contribution drain");

        assertEquals(List.of("idle", "frame", "history", "contribution drain"), events);
    }

    @Test
    void blasBuildUseReleasesScratchAndSourceGenerationTogetherAfterCompute() {
        List<String> events = new ArrayList<>();

        RtRetainedSceneBackend.releaseBuildUseResources(
                () -> events.add("scratch"), List.of(() -> events.add("source generation")));

        assertEquals(List.of("scratch", "source generation"), events);
    }

    @Test
    void blasBuildUseStillReleasesSourceWhenScratchReleaseFails() {
        AtomicInteger sourceReleases = new AtomicInteger();
        RuntimeException scratchFailure = new RuntimeException("scratch");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RtRetainedSceneBackend.releaseBuildUseResources(
                        () -> { throw scratchFailure; }, List.of(sourceReleases::incrementAndGet)));

        assertSame(scratchFailure, thrown);
        assertEquals(1, sourceReleases.get());
    }

    @Test
    void terminalFrameRootsRetireDisplacedSnapshotBeforeContributionDrain() {
        AtomicInteger retirements = new AtomicInteger();
        SharedResource<String> published = SharedResource.owned(
                "revision-1", ignored -> retirements.incrementAndGet());
        SharedResource<String> frame = published.retain();
        SharedResource<String> history = published.retain();
        published.close();
        Map<Object, AutoCloseable> frames = new LinkedHashMap<>();
        Map<Object, AutoCloseable> histories = new LinkedHashMap<>();
        frames.put(new Object(), frame);
        histories.put(new Object(), history);

        assertEquals(0, retirements.get());
        RtRetainedSceneBackend.releaseTerminalFrameRoots(frames, histories);

        assertEquals(1, retirements.get());
        assertEquals(0, frames.size());
        assertEquals(0, histories.size());
    }

    @Test
    void displacedMotionHistoryLivesUntilItsOverlappingFrameCompletes() {
        AtomicInteger releases = new AtomicInteger();
        SharedResource<String> mappedHistory = SharedResource.owned(
                "submitted frame 1", ignored -> releases.incrementAndGet());
        SharedResource<String> overlappingFrame = mappedHistory.retain();

        mappedHistory.close();

        assertEquals(0, releases.get());
        assertEquals("submitted frame 1", overlappingFrame.get());
        overlappingFrame.close();
        assertEquals(1, releases.get());
    }

    @Test
    void displacedMotionHistoryWaitsForEveryOverlappingConsumer() {
        AtomicInteger releases = new AtomicInteger();
        SharedResource<String> mappedHistory = SharedResource.owned(
                "submitted frame 1", ignored -> releases.incrementAndGet());
        SharedResource<String> firstFrame = mappedHistory.retain();
        SharedResource<String> secondFrame = mappedHistory.retain();

        mappedHistory.close();
        firstFrame.close();

        assertEquals(0, releases.get());
        assertEquals("submitted frame 1", secondFrame.get());
        secondFrame.close();
        assertEquals(1, releases.get());
    }

    @Test
    void terminalFrameRootReleaseDoesNotRetireCurrentPublication() {
        AtomicInteger retirements = new AtomicInteger();
        SharedResource<String> current = SharedResource.owned(
                "revision-2", ignored -> retirements.incrementAndGet());
        Map<Object, AutoCloseable> frames = new LinkedHashMap<>();
        frames.put(new Object(), current.retain());

        RtRetainedSceneBackend.releaseTerminalFrameRoots(frames, new LinkedHashMap<>());

        assertEquals("revision-2", current.get());
        assertEquals(0, retirements.get());
        current.close();
        assertEquals(1, retirements.get());
    }

    @Test
    void terminalFrameRootReleaseContinuesAfterOneLeaseFails() {
        AtomicInteger releases = new AtomicInteger();
        Map<Object, AutoCloseable> frames = new LinkedHashMap<>();
        frames.put(new Object(), () -> { throw new IllegalStateException("frame"); });
        Map<Object, AutoCloseable> histories = new LinkedHashMap<>();
        histories.put(new Object(), releases::incrementAndGet);

        assertThrows(IllegalStateException.class,
                () -> RtRetainedSceneBackend.releaseTerminalFrameRoots(frames, histories));

        assertEquals(1, releases.get());
        assertEquals(0, frames.size());
        assertEquals(0, histories.size());
    }

    @Test
    void childReleaseContinuesAfterFailuresAndAggregatesThem() {
        AtomicInteger releases = new AtomicInteger();
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RtRetainedSceneBackend.closeAll(List.of(
                        () -> { releases.incrementAndGet(); throw first; },
                        releases::incrementAndGet,
                        () -> { releases.incrementAndGet(); throw second; }
                ), null));

        assertSame(first, thrown);
        assertEquals(3, releases.get());
        assertEquals(List.of(second), List.of(thrown.getSuppressed()));
    }

    @Test
    void childReleaseFailuresAreSuppressedOntoAnActiveFailure() {
        RuntimeException active = new RuntimeException("active");
        RuntimeException closeFailure = new RuntimeException("close");

        RtRetainedSceneBackend.closeAll(List.of(() -> { throw closeFailure; }), active);

        assertEquals(List.of(closeFailure), List.of(active.getSuppressed()));
    }

    @Test
    void rejectedCleanupFailureIsSuppressedOntoTheRejection() {
        RuntimeException rejection = new RuntimeException("rejection");
        RuntimeException cleanupFailure = new RuntimeException("cleanup");

        RtRetainedSceneBackend.suppressCleanupFailure(rejection, () -> { throw cleanupFailure; });

        assertEquals(List.of(cleanupFailure), List.of(rejection.getSuppressed()));
    }

    @Test
    void rejectedCleanupCannotSuppressTheFailureOntoItself() {
        RuntimeException rejection = new RuntimeException("rejection");

        RtRetainedSceneBackend.suppressCleanupFailure(rejection, () -> { throw rejection; });

        assertEquals(0, rejection.getSuppressed().length);
    }

    @Test
    void failedOwnershipWrapperDisposesTheUnhandedResource() {
        AtomicInteger disposals = new AtomicInteger();
        RuntimeException allocationFailure = new RuntimeException("wrapper allocation");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RtRetainedSceneBackend.handoffResource("native allocation",
                        ignored -> disposals.incrementAndGet(), ignored -> { throw allocationFailure; }));

        assertSame(allocationFailure, thrown);
        assertEquals(1, disposals.get());
    }

    @Test
    void publicationIsTrackedBeforeAcceptanceCanComplete() {
        ArrayDeque<Object> queue = new ArrayDeque<>();
        Object publication = new Object();

        RtRetainedSceneBackend.queueBeforeAcceptance(queue, publication,
                () -> assertSame(publication, queue.getLast()));

        assertSame(publication, queue.getLast());
    }

    @Test
    void rejectedAcceptanceRollsBackItsQueueSlot() {
        ArrayDeque<Object> queue = new ArrayDeque<>();
        Object predecessor = new Object();
        Object publication = new Object();
        RuntimeException rejection = new RuntimeException("rejected");
        queue.add(predecessor);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RtRetainedSceneBackend.queueBeforeAcceptance(
                        queue, publication, () -> { throw rejection; }));

        assertSame(rejection, thrown);
        assertEquals(List.of(predecessor), List.copyOf(queue));
    }
}
