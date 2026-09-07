package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RtDenseLightIndexTest {
    @Test void sharedPagesMoveWithoutReadingTheirLightIdentities() {
        var reads = new AtomicInteger();
        var index = new RtDenseLightIndex<Light>(4, light -> {
            reads.incrementAndGet();
            return light.identity;
        });
        var first = List.of(new Light(0, 1), new Light(72, 1));
        var second = List.of(new Light(15, 1));
        index.update(SnapshotList.ofPages(List.of(first, second)));
        assertEquals(3, reads.get());
        reads.set(0);
        index.update(SnapshotList.ofPages(List.of(second, first)));
        assertEquals(0, reads.get());
        assertEquals(0, index.get(15));
        assertEquals(1, index.get(0));
        assertEquals(2, index.get(72));
        assertEquals(-1, index.get(99));
        assertEquals(-7, index.getOrDefault(99, -7));
        assertFalse(index.containsKey(99));
        assertTrue(index.containsKey(0));
    }

    @Test void descriptorReplacementOnlyReadsReplacedPages() {
        var reads = new AtomicInteger();
        var index = new RtDenseLightIndex<Light>(3, light -> {
            reads.incrementAndGet();
            return light.identity;
        });
        var stable = List.of(new Light(10, 1), new Light(20, 1));
        var edited = List.of(new Light(30, 1));
        index.update(SnapshotList.ofPages(List.of(stable, edited)));
        reads.set(0);
        index.update(SnapshotList.ofPages(List.of(stable, List.of(new Light(30, 9)))));
        assertEquals(2, reads.get());
        assertEquals(0, index.get(10));
        assertEquals(1, index.get(20));
        assertEquals(2, index.get(30));
    }

    @Test void removalShiftsBasesAndReusedSlotsDoNotKeepRemovedIdentities() {
        var index = new RtDenseLightIndex<Light>(4, Light::identity);
        var removed = List.of(new Light(1, 1), new Light(2, 1));
        var stable = List.of(new Light(3, 1));
        index.update(SnapshotList.ofPages(List.of(removed, stable)));
        index.update(SnapshotList.ofPages(List.of(stable, List.of(new Light(4, 1)))));
        assertEquals(-1, index.get(1));
        assertEquals(-1, index.get(2));
        assertEquals(0, index.get(3));
        assertEquals(1, index.get(4));
        index.update(List.of());
        assertFalse(index.containsKey(3));
        assertEquals(-1, index.get(4));
        index.update(removed);
        assertEquals(0, index.get(1));
        assertEquals(1, index.get(2));
        assertEquals(-1, index.get(3));
    }

    @Test void identitiesMigrateBetweenReplacementPagesBeforeDenseBasesAreResolved() {
        var index = new RtDenseLightIndex<Light>(4, Light::identity);
        index.update(SnapshotList.ofPages(List.of(List.of(new Light(1, 1), new Light(2, 1)),
                List.of(new Light(3, 1), new Light(4, 1)))));
        index.update(SnapshotList.ofPages(List.of(List.of(new Light(4, 2), new Light(1, 2)),
                List.of(new Light(2, 2), new Light(3, 2)))));
        assertEquals(0, index.get(4));
        assertEquals(1, index.get(1));
        assertEquals(2, index.get(2));
        assertEquals(3, index.get(3));
    }

    @Test void sourceTrackingDetectsAnUpdateWhoseConsumingFrameDidNotFinish() {
        var index = new RtDenseLightIndex<Light>(2, Light::identity);
        var original = List.of(new Light(1, 1));
        var edited = List.of(new Light(2, 1));
        index.update(original);
        assertTrue(index.matches(original));
        index.update(edited);
        assertFalse(index.matches(original));
        assertTrue(index.matches(edited));
        index.update(original);
        assertEquals(0, index.get(1));
        assertEquals(-1, index.get(2));
    }

    private record Light(long identity, int descriptor) {}
}
