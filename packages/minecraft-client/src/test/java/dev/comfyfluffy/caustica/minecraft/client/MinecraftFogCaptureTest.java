package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFogCaptureTest {
    @Test void alignsToNearestCenterOnBothSidesOfZero() {
        assertEquals(-512, MinecraftFogCapture.captureOrigin(-31.999));
        assertEquals(-512, MinecraftFogCapture.captureOrigin(31.999));
        assertEquals(-576, MinecraftFogCapture.captureOrigin(-32.001));
        assertEquals(-448, MinecraftFogCapture.captureOrigin(32.001));
        for (double coordinate : new double[]{-29_999_999.75, -1600.1, -64, -32, 0, 32, 64, 1600.1, 29_999_999.75}) {
            int center = MinecraftFogCapture.captureOrigin(coordinate) + MinecraftFogCapture.RADIUS;
            assertEquals(0, Math.floorMod(center, 64));
            assertTrue(Math.abs(center - coordinate) <= 32);
        }
    }

    @Test void fullFogRangeRemainsInsideUnfadedGridUntilFollowingRevisionCompletes() {
        int revisionFrames = Math.ceilDiv(MinecraftFogCapture.WIDTH * MinecraftFogCapture.WIDTH,
                MinecraftFogCapture.COLUMNS_PER_FRAME);
        assertEquals(35, revisionFrames);
        double maxAgeSeconds = 2.0 * revisionFrames / 50;
        double travel = 87.1 * maxAgeSeconds;
        double fogRange = 256;
        double edgeFade = MinecraftFogCapture.SPACING;
        for (double initial : new double[]{-4096.001, -32.001, -32, 0, 31.999, 32, 4096.001}) {
            int origin = MinecraftFogCapture.captureOrigin(initial);
            double unfadedMin = origin + edgeFade;
            double unfadedMax = origin + (MinecraftFogCapture.WIDTH - 1) * MinecraftFogCapture.SPACING - edgeFade;
            assertTrue(initial - travel - fogRange >= unfadedMin);
            assertTrue(initial + travel + fogRange <= unfadedMax);
        }
    }
}
