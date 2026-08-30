package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelector;

import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFrameAdapterTest {
    @Test
    void cameraBelowWaterSurfaceIsSubmerged() {
        assertTrue(MinecraftFrameAdapter.isSubmergedInWater(true, 63.749, 63, 0.75f));
    }

    @Test
    void cameraExactlyAtWaterSurfaceIsNotSubmerged() {
        assertFalse(MinecraftFrameAdapter.isSubmergedInWater(true, 63.75, 63, 0.75f));
    }

    @Test
    void dryAndNonWaterCellsAreNotSubmerged() {
        assertFalse(MinecraftFrameAdapter.isSubmergedInWater(true, 63.0, 63, 0.0f));
        assertFalse(MinecraftFrameAdapter.isSubmergedInWater(false, 63.1, 63, 1.0f));
    }

    @Test
    void epochLeasePublishesAndRemovesOnlyItsOwnSelection() {
        MinecraftFrameAdapter adapter = adapter();
        var binding = ShaderDataType.create("water binding");
        var instance = ShaderDataType.create("water instance");
        SceneId firstScene = new TestScene();
        SceneId secondScene = new TestScene();
        var first = new MinecraftFrameSelector(firstScene, new TestVolume<>(),
                binding.data(1L), instance.data(2L));
        var second = new MinecraftFrameSelector(secondScene, new TestVolume<>(),
                binding.data(3L), instance.data(4L));

        assertNull(adapter.selection(false));
        var firstLease = adapter.installFrameSelector(first);
        assertSame(firstScene, adapter.selection(false).scene());
        var secondLease = adapter.installFrameSelector(second);
        firstLease.close();
        assertSame(secondScene, adapter.selection(false).scene());
        secondLease.close();
        assertNull(adapter.selection(false));
    }

    @Test
    void uiSnapshotDoesNotRetainPriorFrameResources() {
        UiPresentationResources first = MinecraftUiOverlay.snapshotPresentation(
                true, true, image(), 1920, 1080);
        UiPresentationResources second = MinecraftUiOverlay.snapshotPresentation(
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

    private static MinecraftFrameAdapter adapter() {
        MinecraftTelemetry.Instrumentation instrumentation = MinecraftTelemetry.disabled();
        return new MinecraftFrameAdapter(new RtTerrain(new RtWorkerPool(), instrumentation), instrumentation);
    }

    private static final class TestScene implements SceneId { }
    private static final class TestVolume<B, N> implements VolumeId<B, N> { }
}
