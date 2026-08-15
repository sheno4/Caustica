package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.OpenPbrTextureTexel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtTemporalAlphaRangeTest {
    @Test
    void reducesEveryUniqueFramePerTexelWithoutLosingSpatialVariation() {
        MaterialTextureImage image = new Frames(new int[][]{
                {0, 255}, {255, 128}, {64, 128}
        });
        RtMaterialPageCompiler.TemporalAlpha range = RtMaterialPageCompiler.scanTemporalAlpha(image, 2, 1);
        assertEquals(0.0f, range.minAlpha());
        assertEquals(1.0f, range.maxAlpha());
        assertArrayEquals(new float[]{0.0f, 1.0f, 0.0f, 0.0f,
                128 / 255.0f, 1.0f, 0.0f, 0.0f}, range.texels());
    }

    @Test
    void staticTextureHasIdenticalTemporalBounds() {
        RtMaterialPageCompiler.TemporalAlpha range = RtMaterialPageCompiler.scanTemporalAlpha(
                new Frames(new int[][]{{32}}), 1, 1);
        assertEquals(32 / 255.0f, range.texels()[0]);
        assertEquals(range.texels()[0], range.texels()[1]);
    }

    private record Frames(int[][] alpha) implements MaterialTextureImage {
        @Override public int width() { return alpha[0].length; }
        @Override public int height() { return 1; }
        @Override public int albedoArgb(int x, int y) { return alpha[0][x] << 24; }
        @Override public int alphaFrameCount() { return alpha.length; }
        @Override public int alphaArgb(int frame, int x, int y) { return alpha[frame][x] << 24; }
        @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
        @Override public void close() { }
    }
}
