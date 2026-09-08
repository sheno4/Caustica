package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotPagesTest {
    @Test void editsReplaceOnlyTheirPageAndPreserveDenseIndexedIteration() {
        var pages = new SnapshotPages<Integer>();
        var disposed = new AtomicInteger();
        var owner = SharedResource.owned(0, ignored -> disposed.incrementAndGet());
        for (int i = 0; i < 400; i++) pages.put(i, i, owner);
        pages.commit();
        var first = pages.capture();
        var original = (SnapshotList<Integer>) first.get();
        assertEquals(IntStream.range(0, 400).boxed().toList(), original);
        pages.put(150, -1, owner);
        pages.commit();
        var changed = pages.capture();
        var values = (SnapshotList<Integer>) changed.get();
        assertSame(original.pages().get(0), values.pages().get(0));
        assertNotSame(original.pages().get(1), values.pages().get(1));
        assertSame(original.pages().get(2), values.pages().get(2));
        assertEquals(-1, values.get(150));
        assertEquals(150, original.get(150));
        for (int i = 0; i < 384; i++) pages.remove(i);
        pages.commit();
        try (var sparse = pages.capture()) {
            assertEquals(IntStream.range(384, 400).boxed().toList(), sparse.get());
            assertEquals(384, sparse.get().getFirst());
            assertEquals(399, sparse.get().getLast());
            assertEquals(List.copyOf(sparse.get()), sparse.get().stream().toList());
        }
        for (int i = 384; i < 400; i++) pages.remove(i);
        pages.commit();
        owner.close();
        first.close();
        assertEquals(0, disposed.get());
        changed.close();
        assertEquals(1, disposed.get());
        try (var empty = pages.capture()) { assertTrue(empty.get().isEmpty()); }
    }

    @Test void capturesOnlyObserveCommittedDirectories() {
        var pages = new SnapshotPages<Integer>();
        pages.put(0, 10);
        pages.commit();
        try (var first = pages.capture()) {
            pages.put(0, 20);
            pages.put(256, 30);
            try (var duringEdit = pages.capture()) {
                assertSame(first.get(), duringEdit.get());
                assertEquals(List.of(10), duringEdit.get());
            }
            pages.commit();
            try (var after = pages.capture(); var repeated = pages.capture()) {
                assertEquals(List.of(20, 30), after.get());
                assertSame(after.get(), repeated.get());
                assertEquals(List.of(10), first.get());
            }
        }
    }

    @Test void sparseDirectoryEditsPreserveOrderingAcrossBranchInsertionAndCollapse() {
        var pages = new SnapshotPages<Long>();
        var expected = new TreeMap<Long, Long>();
        var random = new Random(437);
        var old = new ArrayList<dev.comfyfluffy.caustica.support.SharedResource<List<Long>>>();
        var oldValues = new ArrayList<List<Long>>();
        for (int batch = 0; batch < 80; batch++) {
            for (int edit = 0; edit < 30; edit++) {
                long key = ((long) random.nextInt(512) << 32) + random.nextInt(400);
                long value = random.nextLong();
                pages.put(key, value);
                expected.put(key, value);
            }
            var keys = List.copyOf(expected.keySet());
            for (int remove = 0; remove < 15; remove++) {
                long key = keys.get(random.nextInt(keys.size()));
                if (expected.remove(key) != null) pages.remove(key);
            }
            pages.commit();
            try (var current = pages.capture()) {
                var values = List.copyOf(expected.values());
                assertEquals(values.size(), current.get().size());
                for (int index = 0; index < values.size(); index++) {
                    assertEquals(values.get(index), current.get().get(index));
                }
                assertEquals(values, current.get().stream().toList());
                if (batch % 10 == 0) {
                    old.add(current.retain());
                    oldValues.add(values);
                }
            }
        }
        for (long key : expected.keySet()) pages.remove(key);
        pages.commit();
        try (var empty = pages.capture()) { assertTrue(empty.get().isEmpty()); }
        for (int index = 0; index < old.size(); index++) {
            assertEquals(oldValues.get(index), old.get(index).get());
            old.get(index).close();
        }
    }

    @Test void transientBucketEditsDoNotPublishOrRetainTheirResources() {
        var pages = new SnapshotPages<Integer>();
        var disposed = new AtomicInteger();
        var owner = SharedResource.owned(0, ignored -> disposed.incrementAndGet());
        pages.put(1, 1, owner);
        pages.remove(1);
        pages.commit();
        owner.close();
        assertEquals(1, disposed.get());
        try (var empty = pages.capture()) { assertTrue(empty.get().isEmpty()); }
    }
}
