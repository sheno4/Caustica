package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtSceneGeometryHistoryWriterTest {
    private static final int TRANSFORM_FLOATS = 12;

    @Test
    void writerCopiesBasisAndRebasesOnlyTranslationsAfterDoubleSubtraction() {
        float[] authored = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        RtSceneGeometryManager.Placement placement = new RtSceneGeometryManager.Placement(10, authored, 0xff,
                new SceneOrigin(30_000_000.75, -40_000_000.75, 50_000_000.5));
        long allocation = MemoryUtil.nmemAllocChecked((long) (TRANSFORM_FLOATS + 2) * Float.BYTES);
        long address = allocation + Float.BYTES;
        try {
            MemoryUtil.memPutFloat(allocation, -123.5f);
            MemoryUtil.memPutFloat(address + (long) TRANSFORM_FLOATS * Float.BYTES, 456.25f);
            RtSceneGeometryManager.writeHistory(address, placement,
                    new SceneOrigin(30_000_000.25, -40_000_000.25, 50_000_000.25));

            assertArrayEquals(new float[] {1, 2, 3, 4.5f, 5, 6, 7, 7.5f, 9, 10, 11, 12.25f},
                    readTransform(address));
            assertArrayEquals(authored, placement.transform);
            assertEquals(-123.5f, MemoryUtil.memGetFloat(allocation));
            assertEquals(456.25f,
                    MemoryUtil.memGetFloat(address + (long) TRANSFORM_FLOATS * Float.BYTES));
        } finally {
            MemoryUtil.nmemFree(allocation);
        }
    }

    @Test
    void placementOwnershipRejectsTransformsOutsideTheHistoryAbiWidth() {
        assertThrows(IllegalArgumentException.class,
                () -> new RtSceneGeometryManager.Placement(10, new float[11], 0xff, SceneOrigin.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RtSceneGeometryManager.Placement(10, new float[13], 0xff, SceneOrigin.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RtSceneGeometryManager.PlacementUpdate(new float[11], 0xff, SceneOrigin.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RtSceneGeometryManager.PlacementUpdate(new float[13], 0xff, SceneOrigin.ZERO));
    }

    @Test
    void writerUsesCurrentPlacementUntilTheFirstSnapshotThenThePreviousSnapshot() {
        RtSceneGeometryManager.Placement first = new RtSceneGeometryManager.Placement(10,
                transform(1f), 0xff, new SceneOrigin(10, 20, 30));
        RtSceneGeometryManager.PublishedPlacement published = new RtSceneGeometryManager.PublishedPlacement(
                new RtSceneGeometryManager.InstanceId(ResourceId.of("test", "history"), SceneGeometryKey.of(1)),
                first, null);
        long address = MemoryUtil.nmemAllocChecked((long) TRANSFORM_FLOATS * Float.BYTES);
        try {
            RtSceneGeometryManager.writeHistory(address, published.historyPlacement(), SceneOrigin.ZERO);
            assertArrayEquals(rebasedTransform(1f, 10f, 20f, 30f), readTransform(address));

            published.completeFrameSnapshot();
            published.placement = new RtSceneGeometryManager.Placement(10,
                    transform(20f), 0xff, new SceneOrigin(1, 2, 3));
            RtSceneGeometryManager.writeHistory(address, published.historyPlacement(), SceneOrigin.ZERO);
            assertArrayEquals(rebasedTransform(1f, 10f, 20f, 30f), readTransform(address));

            published.completeFrameSnapshot();
            RtSceneGeometryManager.writeHistory(address, published.historyPlacement(), SceneOrigin.ZERO);
            assertArrayEquals(rebasedTransform(20f, 1f, 2f, 3f), readTransform(address));
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }

    private static float[] readTransform(long address) {
        float[] transform = new float[TRANSFORM_FLOATS];
        for (int i = 0; i < transform.length; i++) {
            transform[i] = MemoryUtil.memGetFloat(address + (long) i * Float.BYTES);
        }
        return transform;
    }

    private static float[] transform(float base) {
        return new float[] {
                base, base + 1, base + 2, base + 3,
                base + 4, base + 5, base + 6, base + 7,
                base + 8, base + 9, base + 10, base + 11
        };
    }

    private static float[] rebasedTransform(float base, float x, float y, float z) {
        float[] transform = transform(base);
        transform[3] += x;
        transform[7] += y;
        transform[11] += z;
        return transform;
    }
}
