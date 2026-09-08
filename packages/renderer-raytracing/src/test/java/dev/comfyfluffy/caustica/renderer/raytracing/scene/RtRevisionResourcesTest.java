package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtRevisionResourcesTest {
    @Test
    void releasesEachResourceOnceInReverseAcquisitionOrder() {
        var resources = new RtRevisionResources();
        var released = new ArrayList<Integer>();
        resources.add(() -> released.add(1));
        resources.add(() -> released.add(2));
        resources.add(() -> released.add(3));

        resources.close();
        resources.close();

        assertEquals(List.of(3, 2, 1), released);
    }

    @Test
    void repeatedFailureDoesNotInterruptTheRemainingReleases() {
        var resources = new RtRevisionResources();
        var released = new ArrayList<Integer>();
        var failure = new IllegalStateException("release failed");
        var other = new IllegalArgumentException("another release failed");
        resources.add(() -> released.add(1));
        resources.add(() -> { released.add(2); throw other; });
        resources.add(() -> { released.add(3); throw failure; });
        resources.add(() -> { released.add(4); throw failure; });

        assertSame(failure, assertThrows(IllegalStateException.class, resources::close));
        assertEquals(List.of(4, 3, 2, 1), released);
        assertArrayEquals(new Throwable[]{other}, failure.getSuppressed());
        assertDoesNotThrow(resources::close);
    }
}
