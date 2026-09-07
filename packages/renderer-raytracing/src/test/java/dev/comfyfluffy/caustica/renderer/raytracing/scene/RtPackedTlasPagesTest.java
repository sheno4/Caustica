package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RtPackedTlasPagesTest {
    private static final SceneOrigin ORIGIN = new SceneOrigin(17, -3, 29);

    @Test void reusesUnchangedCurrentPageAcrossFrameContainers() {
        var cache = new RtPackedTlasPages();
        var range = range();
        var calls = new AtomicInteger();
        TlasBuilder.InstanceWriter<Input> writer = (input, target) -> {
            calls.incrementAndGet();
            write(input, target, ORIGIN);
        };
        var current = List.of(new Input(range, 7, 2));
        var first = cache.resolve(SnapshotList.ofPages(List.of(current)), ORIGIN, writer);
        var second = cache.resolve(SnapshotList.ofPages(List.of(current)), new SceneOrigin(17, -3, 29), writer);
        assertSame(first.getFirst(), second.getFirst());
        assertEquals(1, calls.get());
        assertFalse(first.getFirst().isDirect());
        assertTrue(first.getFirst().isReadOnly());
    }

    @Test void changingNonFirstInstanceRebuildsOnlyItsCurrentPage() {
        var cache = new RtPackedTlasPages();
        var first = new Input(range(), 7, 2);
        var page = List.of(first, new Input(range(), 9, 2));
        var stable = List.of(new Input(range(), 5, 5));
        var before = resolve(cache, SnapshotList.ofPages(List.of(page, stable)), ORIGIN);
        var changed = List.of(first, new Input(range(), 13, 9));
        var after = resolve(cache, SnapshotList.ofPages(List.of(changed, stable)), ORIGIN);
        assertNotEquals(before.getFirst(), after.getFirst());
        assertSame(before.getLast(), after.getLast());
        assertEquals(before.getFirst(), resolve(new RtPackedTlasPages(), page, ORIGIN).getFirst());
    }

    @Test void newRangeGenerationAndOriginRepackCurrentFields() {
        var cache = new RtPackedTlasPages();
        var firstRange = range();
        var replacementRange = range();
        assertEquals(firstRange, replacementRange);
        var first = resolve(cache, List.of(new Input(firstRange, 7, 2)), ORIGIN);
        var replacementInput = List.of(new Input(replacementRange, 13, 7));
        var replacement = resolve(cache, replacementInput, ORIGIN);
        assertNotSame(first.getFirst(), replacement.getFirst());
        assertNotEquals(first.getFirst(), replacement.getFirst());
        var rebased = resolve(cache, replacementInput, new SceneOrigin(18, -3, 29));
        assertNotSame(replacement.getFirst(), rebased.getFirst());
        assertNotEquals(replacement.getFirst(), rebased.getFirst());
    }

    @Test void concatenatedPagesMatchSequentialStructPackingIncludingScratchGrowth() {
        var firstRange = range();
        var secondRange = range();
        var inputs = SnapshotList.ofPages(List.of(List.of(new Input(firstRange, 2, 9)),
                List.of(new Input(secondRange, 19, 1), new Input(secondRange, 7, 3))));
        var packed = resolve(new RtPackedTlasPages(), inputs, ORIGIN);
        int bytes = inputs.size() * VkAccelerationStructureInstanceKHR.SIZEOF;
        ByteBuffer sequential = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        var records = VkAccelerationStructureInstanceKHR.create(MemoryUtil.memAddress(sequential), inputs.size());
        int index = 0;
        for (Input input : inputs) write(input, records.get(index++), ORIGIN);
        ByteBuffer combined = ByteBuffer.allocate(bytes);
        for (ByteBuffer page : packed) combined.put(page.duplicate());
        combined.flip();
        assertEquals(sequential, combined);
        assertEquals(0, packed.getFirst().position());
        assertEquals(0, packed.getLast().position());
    }

    @Test void removingPagesEvictsTheirBytes() {
        var cache = new RtPackedTlasPages();
        var first = List.of(new Input(range(), 7, 2));
        var second = List.of(new Input(range(), 13, 4));
        var initial = resolve(cache, SnapshotList.ofPages(List.of(first, second)), ORIGIN);
        var remaining = resolve(cache, second, ORIGIN);
        assertSame(initial.getLast(), remaining.getFirst());
        var returned = resolve(cache, SnapshotList.ofPages(List.of(first, second)), ORIGIN);
        assertNotSame(initial.getFirst(), returned.getFirst());
        assertSame(initial.getLast(), returned.getLast());
        assertTrue(resolve(cache, List.of(), ORIGIN).isEmpty());
        assertNotSame(returned.getLast(), resolve(cache, second, ORIGIN).getFirst());
    }

    private static List<ByteBuffer> resolve(RtPackedTlasPages cache, List<Input> inputs, SceneOrigin origin) {
        return cache.resolve(inputs, origin, (input, target) -> write(input, target, origin));
    }

    private static void write(Input input, VkAccelerationStructureInstanceKHR target, SceneOrigin origin) {
        int value = input.current;
        for (int component = 0; component < 12; component++) {
            target.transform().matrix(component, value + component * 0.25f);
        }
        target.transform().matrix(3, (float) (value - origin.x()));
        target.transform().matrix(7, (float) (value - origin.y()));
        target.transform().matrix(11, (float) (value - origin.z()));
        target.instanceCustomIndex(value * 3).mask(value + 1)
                .instanceShaderBindingTableRecordOffset(value * 6).flags(value + 2)
                .accelerationStructureReference(0x100000000L + value * 256L);
    }

    private static RtStableTraceRanges.PageRange range() {
        return new RtStableTraceRanges.PageRange(0, 3, 0, 0);
    }

    private record Input(RtStableTraceRanges.PageRange range, int current, int previous) {}
}
