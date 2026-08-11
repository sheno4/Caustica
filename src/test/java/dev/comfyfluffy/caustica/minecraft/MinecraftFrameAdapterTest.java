package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import org.junit.jupiter.api.Test;


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
}
