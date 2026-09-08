package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ResourceLifetimeTest {
    @Test void rollbackPreservesThePrimaryFailureAndAttemptsEveryRelease() {
        RuntimeException primary = new IllegalStateException("publication");
        Error cleanup = new AssertionError("descriptor release");
        List<String> released = new ArrayList<>();

        ResourceLifetime.closeAfterFailure(primary,
                () -> { released.add("binding"); throw cleanup; },
                () -> { released.add("atlas"); throw cleanup; },
                () -> released.add("images"));

        assertEquals(List.of("binding", "atlas", "images"), released);
        assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
        assertEquals(0, cleanup.getSuppressed().length);
    }

    @Test void rollbackCanReportTheOriginalFailureWithoutSelfSuppression() {
        Error primary = new AssertionError("allocation");
        List<String> released = new ArrayList<>();

        ResourceLifetime.closeAfterFailure(primary,
                () -> { throw primary; }, () -> released.add("remaining resource"));

        assertEquals(List.of("remaining resource"), released);
        assertEquals(0, primary.getSuppressed().length);
    }


    @Test
    void failedDestructionStillReleasesDependenciesWithoutRetryingNativeDestruction() {
        List<String> destroyed = new ArrayList<>();
        RuntimeException descriptorFailure = new IllegalStateException("descriptor");
        Error viewFailure = new AssertionError("view");
        ResourceLifetime lifetime = new ResourceLifetime(
                () -> { destroyed.add("descriptor"); throw descriptorFailure; },
                () -> { destroyed.add("view"); throw viewFailure; },
                () -> destroyed.add("allocation"));

        assertSame(descriptorFailure, assertThrows(IllegalStateException.class, lifetime::close));
        lifetime.close();

        assertEquals(List.of("descriptor", "view", "allocation"), destroyed);
        assertEquals(List.of(viewFailure), List.of(descriptorFailure.getSuppressed()));
    }

    @Test
    void destructionRunsOnceInDependencyOrder() {
        List<String> destroyed = new ArrayList<>();
        ResourceLifetime lifetime = new ResourceLifetime(
                () -> destroyed.add("descriptor range"),
                () -> destroyed.add("native object"));

        lifetime.close();
        lifetime.close();

        assertEquals(List.of("descriptor range", "native object"), destroyed);
    }
}
