package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.ColorSpaces;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEmissionFootprint;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialEmission;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtLightCollectorTest {
    @Test
    void primitiveGatedEmissionRejectsZeroAndScalesHalfStrength() {
        var emission = emission(100.0f, true, footprint(1, 1.0f, 1.0f));

        Result zero = collect(emission, 0.0f, 0.0f);
        Result half = collect(emission, 0.5f, 0.0f);
        Result full = collect(emission, 1.0f, 0.0f);

        assertTrue(zero.lights.isEmpty());
        assertEquals(RtLightCollector.FLOATS_PER_LIGHT, half.lights.size());
        assertEquals(full.lights.getFloat(16) * 0.5f, half.lights.getFloat(16), 1.0e-5f);
        assertEquals(full.lights.getFloat(17) * 0.5f, half.lights.getFloat(17), 1.0e-5f);
        assertEquals(full.lights.getFloat(18) * 0.5f, half.lights.getFloat(18), 1.0e-5f);
    }

    @Test
    void namedUniformEmissionIgnoresPrimitiveState() {
        Result result = collect(emission(80.0f, false, footprint(1, 1.0f, 1.0f)), 0.0f, 0.0f);

        assertEquals(RtLightCollector.FLOATS_PER_LIGHT, result.lights.size());
    }

    @Test
    void rectangleMeanPreservesTheFootprintPower() {
        float[] weights = {1.0f, 0.0f, 0.0f, 1.0f};
        Result result = collect(emission(120.0f, false, footprint(2, weights, weights)), 0.0f, 0.0f);
        float area = result.lights.getFloat(3);
        float[] white = ColorSpaces.linearBt709ToAcesCg(1.0f, 1.0f, 1.0f);

        assertEquals(1.0f, area, 1.0e-6f);
        assertEquals(white[0] * 60.0f, result.lights.getFloat(16) * area, 1.0e-4f);
        assertEquals(white[1] * 60.0f, result.lights.getFloat(17) * area, 1.0e-4f);
        assertEquals(white[2] * 60.0f, result.lights.getFloat(18) * area, 1.0e-4f);
    }

    @Test
    void sparseAndLowLuminanceCandidatesStayOutsideTheProviderSnapshot() {
        float[] sparse = {
                0, 0, 0,
                1, 0, 1,
                0, 0, 0
        };
        Result belowFill = collect(emission(100.0f, false, footprint(3, sparse, sparse)), 0.0f, 0.8f);
        Result belowLuminance = collect(emission(100.0f, false,
                footprint(1, new float[]{1.0f}, new float[]{0.0001f})), 0.0f, 0.0f);

        assertTrue(belowFill.lights.isEmpty());
        assertTrue(belowLuminance.lights.isEmpty());
    }

    private static Result collect(MinecraftMaterialEmission emission,
                                  float stateEmission, float minFillRatio) {
        FloatArrayList verts = new FloatArrayList(new float[]{
                0, 0, 0,
                1, 0, 0,
                1, 1, 0,
                0, 1, 0
        });
        FloatArrayList prim = new FloatArrayList(24);
        for (int triangle = 0; triangle < 2; triangle++) {
            prim.add(0.0f);
            prim.add(0.0f);
            prim.add(1.0f);
            prim.add(stateEmission);
            prim.add(1.0f);
            prim.add(1.0f);
            prim.add(1.0f);
            for (int lane = 7; lane < 12; lane++) prim.add(0.0f);
        }
        FloatArrayList cornerUv = new FloatArrayList(new float[]{
                0, 0, 1, 0, 1, 1,
                0, 0, 1, 1, 0, 1
        });
        FloatArrayList lights = new FloatArrayList();
        RtLightCollector.collectClass(lights, verts, prim, cornerUv,
                new TextureAtlasSprite[2], new MinecraftMaterialEmission[]{emission, emission},
                minFillRatio);
        return new Result(lights);
    }

    private static MinecraftMaterialEmission emission(
            float luminance, boolean primitiveGated, MinecraftEmissionFootprint footprint) {
        return new MinecraftMaterialEmission(luminance, primitiveGated, footprint);
    }

    private static TestFootprint footprint(int resolution, float weight, float color) {
        float[] weights = new float[resolution * resolution];
        float[] colors = new float[resolution * resolution];
        java.util.Arrays.fill(weights, weight);
        java.util.Arrays.fill(colors, color);
        return footprint(resolution, weights, colors);
    }

    private static TestFootprint footprint(int resolution, float[] weights, float[] colors) {
        return new TestFootprint(resolution, weights, colors);
    }

    private record Result(FloatArrayList lights) {
    }

    private record TestFootprint(int resolution, float[] weights, float[] colors)
            implements MinecraftEmissionFootprint {
        @Override
        public int sampleIndex(float coordinate) {
            return Math.max(0, Math.min(resolution - 1, (int) (coordinate * resolution)));
        }

        @Override public float r(int x, int y) { return colors[y * resolution + x]; }
        @Override public float g(int x, int y) { return colors[y * resolution + x]; }
        @Override public float b(int x, int y) { return colors[y * resolution + x]; }
        @Override public float weight(int x, int y) { return weights[y * resolution + x]; }
    }
}
