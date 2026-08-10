package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.engine.frame.DamageOverlay;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftFrameAdapterTest {
    @Test
    void uiSnapshotDoesNotRetainPriorFrameResources() {
        UiPresentationResources first = MinecraftFrameAdapter.snapshotUiPresentation(
                true, true, 11, 12, 1920, 1080);
        UiPresentationResources second = MinecraftFrameAdapter.snapshotUiPresentation(
                false, false, 0, 0, 0, 0);

        assertEquals(new UiPresentationResources(true, true, 11, 12, 1920, 1080), first);
        assertEquals(UiPresentationResources.EMPTY, second);
    }

    @Test
    void damageSnapshotDoesNotRetainPriorFrameEntries() {
        ArrayList<DamageOverlay> current = new ArrayList<>();
        current.add(new DamageOverlay(2, 3, 4, 5));
        List<DamageOverlay> first = MinecraftFrameAdapter.snapshotDamageOverlays(current);
        current.clear();
        List<DamageOverlay> second = MinecraftFrameAdapter.snapshotDamageOverlays(current);

        assertEquals(List.of(new DamageOverlay(2, 3, 4, 5)), first);
        assertEquals(List.of(), second);
    }
}
