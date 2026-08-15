package dev.comfyfluffy.caustica.minecraft.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class MinecraftMaterialAlphaFramesTest {
    @Test
    void brokenMetadataFallsBackToEveryPhysicalSlotInAMultirowSheet() {
        assertArrayEquals(new int[]{0, 1, 2, 3}, MinecraftMaterialCatalogBuilder.exhaustiveAlphaFrames(
                new int[]{-1, 0, 2, 3, 4, 2}, 32, 32, 16, 16));
    }

    @Test
    void validMetadataRetainsEveryUniqueAdvertisedFrame() {
        assertArrayEquals(new int[]{0, 2, 3}, MinecraftMaterialCatalogBuilder.exhaustiveAlphaFrames(
                new int[]{0, 2, 3, 2}, 32, 32, 16, 16));
    }

    @Test
    void fallsBackToTheValidFirstFrameWhenMetadataHasNoSheetSlot() {
        assertArrayEquals(new int[]{0}, MinecraftMaterialCatalogBuilder.exhaustiveAlphaFrames(
                new int[]{7, -2}, 16, 16, 16, 16));
    }
}
