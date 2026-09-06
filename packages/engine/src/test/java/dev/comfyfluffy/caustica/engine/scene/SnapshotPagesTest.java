package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotPagesTest {
    @Test void editsReplaceOnlyTheirPageAndPreserveDenseIndexedIteration() {
        var pages = new SnapshotPages<Integer>();
        var disposed = new AtomicInteger();
        var owner = SharedResource.owned(0, ignored -> disposed.incrementAndGet());
        for (int i = 0; i < 400; i++) pages.put(i, i, owner);
        var first = pages.capture();
        var original = (SnapshotList<Integer>) first.get();
        assertEquals(IntStream.range(0, 400).boxed().toList(), original);
        pages.put(150, -1, owner);
        var changed = pages.capture();
        var values = (SnapshotList<Integer>) changed.get();
        assertSame(original.pages().get(0), values.pages().get(0));
        assertNotSame(original.pages().get(1), values.pages().get(1));
        assertSame(original.pages().get(2), values.pages().get(2));
        assertEquals(-1, values.get(150));
        assertEquals(150, original.get(150));
        for (int i = 0; i < 384; i++) pages.remove(i);
        try (var sparse = pages.capture()) {
            assertEquals(IntStream.range(384, 400).boxed().toList(), sparse.get());
            assertEquals(384, sparse.get().getFirst());
            assertEquals(399, sparse.get().getLast());
            assertEquals(List.copyOf(sparse.get()), sparse.get().stream().toList());
        }
        for (int i = 384; i < 400; i++) pages.remove(i);
        owner.close();
        first.close();
        assertEquals(0, disposed.get());
        changed.close();
        assertEquals(1, disposed.get());
        try (var empty = pages.capture()) { assertTrue(empty.get().isEmpty()); }
    }
}
