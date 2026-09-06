package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedSceneLifetimeTest {
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
    void motionHistoryRetainsPositionGenerationsWithoutPinningTheSceneRootForNoneStreams() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var resources = directory.openFactory(new ContributionOwner(1));
        AtomicInteger positionRetirements = new AtomicInteger();
        var position = resources.create(positionRetirements::incrementAndGet);
        ResourceOwners frameResources = ResourceOwners.capture(List.of(
                position, ResourceOwner.none()));
        ResourceOwners historyResources = ResourceOwners.capture(List.of(
                position, ResourceOwner.none()));
        AtomicInteger rootRetirements = new AtomicInteger();
        SharedResource<String> sceneRoot = SharedResource.owned(
                "revision with explicit NONE positions", ignored -> rootRetirements.incrementAndGet());

        position.close();
        sceneRoot.close();
        frameResources.close();
        directory.awaitRetirements();

        assertEquals(1, rootRetirements.get(), "motion history must not retain the scene root");
        assertEquals(0, positionRetirements.get(), "motion history still owns the position generation");

        historyResources.close();
        directory.awaitRetirements();
        assertEquals(1, positionRetirements.get());
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

}
