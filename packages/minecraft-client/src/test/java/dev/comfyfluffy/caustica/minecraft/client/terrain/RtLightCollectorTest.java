package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftEmissionFootprint;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftEmissionFootprintFixtures;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtLightCollectorTest {
    @Test void shadingNormalSelectsAxisWindingAndPreservesSkew() {
        var lights = new ArrayList<MinecraftTerrainEmitter>();
        RtLightCollector.append(lights, 0, 0, 0, .8f, .2f, -.4f, 12,
                2, 0, 0, 0, 3, 0, 10, 11, 12);
        assertEquals(-3.0, lights.getFirst().descriptor().halfVy());
        assertEquals(12, lights.getFirst().firstPrimitive());
        assertEquals(2, lights.getFirst().primitiveCount());
        RtLightCollector.append(lights, 0, 0, 0, 0, 1, 0, 14,
                1, .2, 0, 0, .3, 1, 1, 0, 0);
        assertEquals(.2, lights.getLast().descriptor().halfUy());
        assertEquals(.3, Math.abs(lights.getLast().descriptor().halfVy()));
    }

    @Test
    void primitiveGatedEmissionRejectsZeroAndScalesHalfStrength() {
        var emission = emission(100.0f, true, footprint(1, 1.0f, 1.0f));

        Result zero = collect(emission, 0.0f, 0.0f);
        Result half = collect(emission, 0.5f, 0.0f);
        Result full = collect(emission, 1.0f, 0.0f);

        assertTrue(zero.lights.isEmpty());
        assertEquals(1, half.lights.size());
        assertEquals(full.lights.getFirst().descriptor().radianceRedCdM2() * 0.5f, half.lights.getFirst().descriptor().radianceRedCdM2(), 1.0e-5f);
        assertEquals(full.lights.getFirst().descriptor().radianceGreenCdM2() * 0.5f, half.lights.getFirst().descriptor().radianceGreenCdM2(), 1.0e-5f);
        assertEquals(full.lights.getFirst().descriptor().radianceBlueCdM2() * 0.5f, half.lights.getFirst().descriptor().radianceBlueCdM2(), 1.0e-5f);
    }

    @Test
    void namedUniformEmissionIgnoresPrimitiveState() {
        Result result = collect(emission(80.0f, false, footprint(1, 1.0f, 1.0f)), 0.0f, 0.0f);

        assertEquals(1, result.lights.size());
    }

    @Test
    void rectangleMeanPreservesTheFootprintPower() {
        float[] weights = {1.0f, 0.0f, 0.0f, 1.0f};
        Result result = collect(emission(120.0f, false, footprint(2, weights, weights)), 0.0f, 0.0f);
        var light = result.lights.getFirst().descriptor();
        double area = 4.0 * light.halfUx() * light.halfVy();
        float[] white = ColorSpaces.linearBt709ToAcesCg(1.0f, 1.0f, 1.0f);

        assertEquals(1.0f, area, 1.0e-6f);
        assertEquals(white[0] * 60.0f, result.lights.getFirst().descriptor().radianceRedCdM2() * area, 1.0e-4f);
        assertEquals(white[1] * 60.0f, result.lights.getFirst().descriptor().radianceGreenCdM2() * area, 1.0e-4f);
        assertEquals(white[2] * 60.0f, result.lights.getFirst().descriptor().radianceBlueCdM2() * area, 1.0e-4f);
    }

    @Test
    void sparseAndLowLuminanceCandidatesStayOutsideRetainedPublication() {
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
        var lights = new ArrayList<MinecraftTerrainEmitter>();
        RtLightCollector.collectClass(lights, verts, prim, cornerUv,
                java.util.Arrays.asList(null, null), List.of(emission, emission),
                minFillRatio);
        return new Result(lights);
    }

    private static MinecraftMaterialEmission emission(
            float luminance, boolean primitiveGated, MinecraftEmissionFootprint footprint) {
        return new MinecraftMaterialEmission(luminance, primitiveGated, footprint);
    }

    private static MinecraftEmissionFootprint footprint(int resolution, float weight, float color) {
        float[] weights = new float[resolution * resolution];
        float[] colors = new float[resolution * resolution];
        java.util.Arrays.fill(weights, weight);
        java.util.Arrays.fill(colors, color);
        return footprint(resolution, weights, colors);
    }

    private static MinecraftEmissionFootprint footprint(int resolution, float[] weights, float[] colors) {
        return MinecraftEmissionFootprintFixtures.footprint(resolution, weights, colors);
    }

    private record Result(List<MinecraftTerrainEmitter> lights) {
    }

}
