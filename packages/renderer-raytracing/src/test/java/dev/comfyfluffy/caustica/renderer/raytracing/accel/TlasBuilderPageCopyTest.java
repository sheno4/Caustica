package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TlasBuilderPageCopyTest {
    @Test void stablePagesSkipCopiesAndReplacementCopiesOnlyItsBytes() {
        var first = page(1, 2);
        var second = page(3, 4, 5);
        var original = List.of(first, second);
        var target = ByteBuffer.allocate(8);
        target.put(7, (byte) 99);
        assertEquals(5, TlasBuilder.copyPages(target, original, List.of()));
        assertEquals(0, TlasBuilder.copyPages(target, original, original));
        var replacement = page(6, 7);
        var next = List.of(replacement, second);
        assertEquals(2, TlasBuilder.copyPages(target, next, original));
        assertBytes(target, 6, 7, 3, 4, 5);
        assertEquals(99, target.get(7));
        assertEquals(0, first.position());
        assertEquals(0, second.position());
        assertEquals(0, target.position());
    }

    @Test void changedOffsetsCopyUnchangedPageObjects() {
        var first = page(1, 2);
        var second = page(3, 4, 5);
        var original = List.of(first, second);
        var target = ByteBuffer.allocate(16);
        TlasBuilder.copyPages(target, original, List.of());
        var longer = List.of(page(6, 7, 8), second);
        assertEquals(6, TlasBuilder.copyPages(target, longer, original));
        assertBytes(target, 6, 7, 8, 3, 4, 5);
        var shorter = List.of(first, second);
        assertEquals(5, TlasBuilder.copyPages(target, shorter, longer));
        assertBytes(target, 1, 2, 3, 4, 5);
    }

    @Test void insertionRemovalAndReorderingRespectDensePageOrder() {
        var first = page(1, 2);
        var middle = page(3);
        var last = page(4, 5);
        var original = List.of(first, last);
        var target = ByteBuffer.allocate(16);
        TlasBuilder.copyPages(target, original, List.of());
        var inserted = List.of(first, middle, last);
        assertEquals(3, TlasBuilder.copyPages(target, inserted, original));
        assertBytes(target, 1, 2, 3, 4, 5);
        assertEquals(2, TlasBuilder.copyPages(target, original, inserted));
        assertBytes(target, 1, 2, 4, 5);
        var reordered = List.of(last, first);
        assertEquals(4, TlasBuilder.copyPages(target, reordered, original));
        assertBytes(target, 4, 5, 1, 2);
    }

    @Test void shrinkDiscardsTailHistoryAndEmptyHistoryForcesRestoration() {
        var first = page(1, 2);
        var last = page(3, 4);
        var original = List.of(first, last);
        var target = ByteBuffer.allocate(8);
        TlasBuilder.copyPages(target, original, List.of());
        var shrunk = List.of(first);
        assertEquals(0, TlasBuilder.copyPages(target, shrunk, original));
        target.put(2, (byte) 99);
        assertEquals(2, TlasBuilder.copyPages(target, original, shrunk));
        assertBytes(target, 1, 2, 3, 4);
        assertEquals(0, TlasBuilder.copyPages(target, List.of(), original));
        target.put(0, (byte) 88);
        assertEquals(4, TlasBuilder.copyPages(target, original, List.of()));
        assertBytes(target, 1, 2, 3, 4);
    }

    @Test void sourcePositionsAndLimitsAreBorrowedWithoutMutation() {
        var source = page(99, 1, 2, 88);
        source.position(1).limit(3);
        var target = ByteBuffer.allocate(4);
        assertEquals(2, TlasBuilder.copyPages(target, List.of(source), List.of()));
        assertBytes(target, 1, 2);
        assertEquals(1, source.position());
        assertEquals(3, source.limit());
    }

    private static ByteBuffer page(int... values) {
        var bytes = ByteBuffer.allocate(values.length);
        for (int value : values) bytes.put((byte) value);
        return bytes.flip().asReadOnlyBuffer();
    }

    private static void assertBytes(ByteBuffer target, int... expected) {
        for (int index = 0; index < expected.length; index++) {
            assertEquals((byte) expected[index], target.get(index), "byte " + index);
        }
    }
}
