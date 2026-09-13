package dev.comfyfluffy.caustica.minecraft.rendering;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFogFrameTest {
    @Test void dailyDensityPeaksAtDawnAndIsContinuousAcrossDayBoundary() {
        float noon = MinecraftFogFrame.dailyDensity(0);
        float dusk = MinecraftFogFrame.dailyDensity(Math.PI / 2);
        float midnight = MinecraftFogFrame.dailyDensity(Math.PI);
        float dawn = MinecraftFogFrame.dailyDensity(Math.PI * 1.5);
        assertTrue(dawn > midnight);
        assertTrue(midnight > dusk);
        assertTrue(dusk > noon);
        assertEquals(MinecraftFogFrame.dailyDensity(-0.001),
                MinecraftFogFrame.dailyDensity(Math.PI * 2 - 0.001), 0.00001);
    }

    @Test void gridOwnsItsPublishedDataAndPacksColumnHeightAcrossVerticalLayers() {
        float[] coefficients = {1, .5f, 1, 2, .75f, .25f, 3, .6f, 0, 4, .8f, 1};
        float[] heights = {63, 80};
        var grid = new MinecraftFogFrame.Grid(-256, -64, -256, 32, 2, 2, 1,
                coefficients, heights);
        coefficients[0] = 9;
        heights[0] = 999;

        FloatBuffer packed = FloatBuffer.allocate(16);
        grid.writeVoxels(packed);
        assertEquals(16, packed.position());
        assertEquals(1, packed.get(0));
        assertEquals(63, packed.get(3));
        assertEquals(80, packed.get(7));
        assertEquals(3, packed.get(8));
        assertEquals(0, packed.get(10));
        assertEquals(63, packed.get(11));
        assertEquals(80, packed.get(15));
    }
}
