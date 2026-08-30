package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTexture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialUploadPassTest {
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

        assertArrayEquals(new long[]{0L, 64L, 80L}, MinecraftMaterialUploadPass.mipOffsets(texture));
        assertEquals(84L, MinecraftMaterialUploadPass.byteSize(texture));
    }

    private static byte[] rgba(int width, int height) {
        return new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
    }
}
