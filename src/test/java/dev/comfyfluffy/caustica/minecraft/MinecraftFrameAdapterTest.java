package dev.comfyfluffy.caustica.minecraft;


import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftFrameAdapterTest {
    @Test
    void uiSnapshotDoesNotRetainPriorFrameResources() {
        UiPresentationResources first = MinecraftFrameAdapter.snapshotUiPresentation(
                true, true, image(), 1920, 1080);
        UiPresentationResources second = MinecraftFrameAdapter.snapshotUiPresentation(
                false, false, null, 0, 0);

        assertEquals(11, first.colorImage());
        assertEquals(12, first.colorView());
        assertEquals(1920, first.width());
        assertEquals(1080, first.height());
        assertEquals(UiPresentationResources.EMPTY, second);
    }

    private static GpuImage image() {
        return new GpuImage() {
            @Override public long image() { return 11; }
            @Override public long view() { return 12; }
            @Override public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) { throw new UnsupportedOperationException(); }
            @Override public int width() { return 1920; }
            @Override public int height() { return 1080; }
            @Override public int format() { return 0; }
        };
    }
}
