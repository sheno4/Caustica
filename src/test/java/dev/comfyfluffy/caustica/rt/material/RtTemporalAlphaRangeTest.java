package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.OpenPbrTextureTexel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTemporalAlphaRangeTest {
    @Test
    void reducesEveryUniqueFramePerTexelWithoutLosingSpatialVariation() {
        MaterialTextureImage image = new Frames(new int[][]{
                {0, 255}, {255, 128}, {64, 128}
        });
        MaterialTextureAnalyzer.Alpha range = MaterialTextureAnalyzer.scanAlpha(image, 2, 1);
        assertEquals(0.0f, range.minAlpha());
        assertEquals(1.0f, range.maxAlpha());
        assertArrayEquals(new float[]{0.0f, 1.0f, 0.0f, 0.0f,
                128 / 255.0f, 1.0f, 0.0f, 0.0f}, range.texels());
    }

    @Test
    void staticTextureHasIdenticalTemporalBounds() {
        MaterialTextureAnalyzer.Alpha range = MaterialTextureAnalyzer.scanAlpha(
                new Frames(new int[][]{{32}}), 1, 1);
        assertEquals(32 / 255.0f, range.texels()[0]);
        assertEquals(range.texels()[0], range.texels()[1]);
    }

    @Test
    void staticMixedAlphaUsesEngineOwnedR8AtEveryPossibleCutoff() {
        assertEquals(RtMaterialPageCompiler.ALPHA_SOURCE_STATIC_PAGE,
                RtMaterialPageCompiler.alphaSource(1));
        assertTrue(RtMaterialPageCompiler.requiresSpatialAlpha(0.0f, 51 / 255.0f),
                "a runtime cutoff of 0.1 needs spatial samples even when max alpha is only 0.2");
        assertFalse(RtMaterialPageCompiler.requiresSpatialAlpha(51 / 255.0f, 51 / 255.0f));
        assertFalse(RtMaterialPageCompiler.requiresTemporalRange(1, 0.0f, 1.0f));
    }

    @Test
    void animatedMixedAlphaUsesTemporalRangeButUniformOpaqueDoesNotAllocateOne() {
        assertEquals(RtMaterialPageCompiler.ALPHA_SOURCE_ANIMATED_RANGE,
                RtMaterialPageCompiler.alphaSource(3));
        assertTrue(RtMaterialPageCompiler.requiresTemporalRange(3, 0.0f, 1.0f));
        assertTrue(RtMaterialPageCompiler.requiresTemporalRange(3, 0.0f, 51 / 255.0f),
                "animated alpha below 0.5 can straddle a runtime cutoff of 0.1");
        assertFalse(RtMaterialPageCompiler.requiresTemporalRange(3, 0.75f, 0.75f));
    }

    @Test
    void animatedColorWithStableMixedAlphaUsesStaticR8Samples() {
        MaterialTextureAnalyzer.Alpha stable = MaterialTextureAnalyzer.scanAlpha(
                new Frames(new int[][]{{0, 51}, {0, 51}, {0, 51}}), 2, 1);
        MaterialTextureAnalyzer.Alpha changing = MaterialTextureAnalyzer.scanAlpha(
                new Frames(new int[][]{{0, 51}, {51, 0}}), 2, 1);

        assertFalse(MaterialTextureAnalyzer.hasTemporalVariation(stable));
        assertTrue(MaterialTextureAnalyzer.hasTemporalVariation(changing));
    }

    @Test
    void missingRequiredSpatialPagePublishesUnknownInsteadOfNeutralClassification() {
        assertEquals(RtMaterialPageCompiler.ALPHA_SOURCE_NONE,
                RtMaterialPageCompiler.publishedAlphaSource(
                        RtMaterialPageCompiler.ALPHA_SOURCE_STATIC_PAGE, true, false));
        assertEquals(RtMaterialPageCompiler.ALPHA_SOURCE_NONE,
                RtMaterialPageCompiler.publishedAlphaSource(
                        RtMaterialPageCompiler.ALPHA_SOURCE_ANIMATED_RANGE, true, false));
        assertEquals(RtMaterialPageCompiler.ALPHA_SOURCE_STATIC_PAGE,
                RtMaterialPageCompiler.publishedAlphaSource(
                        RtMaterialPageCompiler.ALPHA_SOURCE_STATIC_PAGE, false, false));
    }

    @Test
    void unprovenAlphaStaysUnknown() {
        assertEquals(RtMaterialPageCompiler.ALPHA_SOURCE_NONE,
                RtMaterialPageCompiler.alphaSource(0));
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
