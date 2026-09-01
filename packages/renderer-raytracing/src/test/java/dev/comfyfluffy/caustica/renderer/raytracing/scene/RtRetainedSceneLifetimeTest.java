package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtRetainedSceneLifetimeTest {
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
