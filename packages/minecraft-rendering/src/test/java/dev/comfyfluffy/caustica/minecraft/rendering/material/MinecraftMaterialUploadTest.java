package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTexture;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class MinecraftMaterialUploadTest {
    @Test void stagingCleanupAttemptsEveryReleaseAndReportsFailure() {
        var cleanup = new AssertionError("staging release");
        var released = new ArrayList<Integer>();
        var result = MinecraftMaterialUpload.retireStaging(new GpuComputeCompletion.Succeeded(),
                () -> { released.add(1); throw cleanup; },
                () -> { released.add(2); throw cleanup; },
                () -> released.add(3));

        assertEquals(List.of(1, 2, 3), released);
        assertSame(cleanup, assertInstanceOf(GpuComputeCompletion.Failed.class, result).failure());
    }

    @Test void stagingCleanupPreservesTheUploadFailure() {
        var primary = new IllegalStateException("upload");
        var cleanup = new AssertionError("staging");
        var failed = new GpuComputeCompletion.Failed(primary);

        assertSame(failed, MinecraftMaterialUpload.retireStaging(failed, () -> { throw cleanup; }));
        assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
    }

    @Test
    void preparedTableCoversEveryRealMaterialOrdinalWithFallbackData() {
        var records = MinecraftProgramResources.fallbackRecords(37);

        assertEquals(37, records.size());
        assertTrue(records.stream().allMatch(record -> record.equals(MinecraftMaterialRecord.fallback())));
    }

    @Test
    void packsEveryMipContiguouslyIntoTheStagingBuffer() {
        MinecraftMaterialTexture texture = new MinecraftMaterialTexture(List.of(
                new MinecraftMaterialTexture.Mip(4, 4, rgba(4, 4)),
                new MinecraftMaterialTexture.Mip(2, 2, rgba(2, 2)),
                new MinecraftMaterialTexture.Mip(1, 1, rgba(1, 1))));

        assertArrayEquals(new long[]{0L, 64L, 80L}, MinecraftMaterialUpload.mipOffsets(texture));
        assertEquals(84L, MinecraftMaterialUpload.byteSize(texture));
    }

    private static byte[] rgba(int width, int height) {
        return new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
    }
}
