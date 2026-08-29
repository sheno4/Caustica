package dev.comfyfluffy.caustica.minecraft.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MinecraftEmissionFootprintTest {
    @Test
    void builderPreservesFixedGridAveragingAndCoordinateMembership() {
        int resolution = 16;
        int width = 31;
        int height = 19;
        float[] expected = new float[resolution * resolution * 4];
        int[] counts = new int[resolution * resolution];
        MinecraftEmissionFootprint.Builder builder = new MinecraftEmissionFootprint.Builder(
                resolution, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float weight = ((x * 13 + y * 7) % 17) / 16.0f;
                float r = (x + 1) * 0.03125f * weight;
                float g = (y + 1) * 0.0625f * weight;
                float b = (x + y + 1) * 0.015625f * weight;
                builder.add(x, y, r, g, b, weight);
                int sample = Math.min(resolution - 1, y * resolution / height) * resolution
                        + Math.min(resolution - 1, x * resolution / width);
                int offset = sample * 4;
                expected[offset] += r;
                expected[offset + 1] += g;
                expected[offset + 2] += b;
                expected[offset + 3] += weight;
                counts[sample]++;
            }
        }
        for (int sample = 0; sample < counts.length; sample++) {
            if (counts[sample] == 0) continue;
            float inverseCount = 1.0f / counts[sample];
            for (int lane = 0; lane < 4; lane++) expected[sample * 4 + lane] *= inverseCount;
        }

        MinecraftEmissionFootprint footprint = builder.build();
        assertNotNull(footprint);
        assertEquals(resolution, footprint.resolution());
        for (int y = 0; y < resolution; y++) {
            for (int x = 0; x < resolution; x++) {
                int offset = (y * resolution + x) * 4;
                assertBits(expected[offset], footprint.r(x, y));
                assertBits(expected[offset + 1], footprint.g(x, y));
                assertBits(expected[offset + 2], footprint.b(x, y));
                assertBits(expected[offset + 3], footprint.weight(x, y));
            }
        }
        for (float coordinate : new float[]{-1.0f, 0.0f, 0.03125f, 0.999f, 1.0f, 2.0f}) {
            assertEquals(Math.max(0, Math.min(resolution - 1, (int) (coordinate * resolution))),
                    footprint.sampleIndex(coordinate));
        }
    }

    @Test
    void builderOmitsFootprintsWithoutCoverage() {
        MinecraftEmissionFootprint.Builder builder = new MinecraftEmissionFootprint.Builder(4, 2, 2);
        builder.add(0, 0, 1.0f, 1.0f, 1.0f, 0.0f);
        assertNull(builder.build());
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
