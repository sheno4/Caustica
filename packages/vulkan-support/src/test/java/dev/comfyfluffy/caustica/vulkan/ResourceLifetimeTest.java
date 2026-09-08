package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ResourceLifetimeTest {
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
