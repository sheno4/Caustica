package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFluidSurfaceTest {
    @Test
    void fullSourceContainsCameraBelowWholeBlockSurface() {
        var heights = MinecraftFluidSurface.CornerHeights.full();

        assertEquals(1.0F, heights.heightAt(0.2, 0.8));
        assertTrue(heights.contains(63.999, 63, 0.2, 0.8));
    }

    @Test
    void slopedSurfaceMatchesBothMeshTriangles() {
        var heights = new MinecraftFluidSurface.CornerHeights(0.5F, 0.7F, 0.9F, 0.6F);

        assertEquals(0.7F, heights.heightAt(0.25, 0.75), 1.0e-6F);
        assertEquals(0.65F, heights.heightAt(0.75, 0.25), 1.0e-6F);
        assertEquals(0.9F, heights.heightAt(1.0, 1.0), 1.0e-6F);
    }

    @Test
    void cameraAtOrAboveInterpolatedSurfaceIsDry() {
        var heights = new MinecraftFluidSurface.CornerHeights(0.5F, 0.7F, 0.9F, 0.6F);
        double surfaceY = 63 + heights.heightAt(0.25, 0.75);

        assertTrue(heights.contains(surfaceY - 1.0e-6, 63, 0.25, 0.75));
        assertFalse(heights.contains(surfaceY, 63, 0.25, 0.75));
        assertFalse(heights.contains(surfaceY + 1.0e-6, 63, 0.25, 0.75));
    }
}
