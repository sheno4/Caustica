package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedGeometrySceneStateTest {
    @Test
    void replacementKeepsSlotAndResetInvalidatesDelayedRemoval() {
        RtRetainedGeometryScene.SlotRegistry<Object> slots = new RtRetainedGeometryScene.SlotRegistry<>();
        Object first = new Object();
        RtRetainedGeometryScene.Slot firstSlot = slots.allocate(first);
        Object replacement = new Object();
        RtRetainedGeometryScene.Slot replacementSlot = slots.replace(firstSlot, replacement);

        assertEquals(firstSlot, replacementSlot);
        assertSame(replacement, slots.get(firstSlot.index()));
        assertFalse(slots.remove(firstSlot, first));
        assertTrue(slots.remove(replacementSlot, replacement));

        RtRetainedGeometryScene.Slot reused = slots.allocate(first);
        assertEquals(firstSlot.index(), reused.index());
        slots.reset();
        assertFalse(slots.remove(reused, first));
        RtRetainedGeometryScene.Slot afterReset = slots.allocate(replacement);
        assertEquals(0, afterReset.index());
        assertFalse(reused.equals(afterReset));
    }

    @Test
    void instanceTransformUsesStableRebasedTranslation() {
        assertArrayEquals(new float[]{1, 0, 0, 16, 0, 1, 0, -32, 0, 0, 1, 48},
                RtRetainedGeometryScene.instanceTransform(160, 64, -80, 144, 96, -128));
    }
}
