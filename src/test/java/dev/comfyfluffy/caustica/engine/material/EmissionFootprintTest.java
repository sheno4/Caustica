package dev.comfyfluffy.caustica.engine.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EmissionFootprintTest {
    @Test
    void sixteenBySixteenBuilderExactlyMatchesThePreviousAveragingAndMembership() {
        int resolution = 16;
        int width = 31;
        int height = 19;
        float[] expected = new float[resolution * resolution * 4];
        int[] counts = new int[resolution * resolution];
        EmissionFootprint.Builder builder = new EmissionFootprint.Builder(resolution, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float weight = ((x * 13 + y * 7) % 17) / 16.0f;
                float r = (x + 1) * 0.03125f * weight;
                float g = (y + 1) * 0.0625f * weight;
                float b = (x + y + 1) * 0.015625f * weight;
                builder.add(x, y, r, g, b, weight);

                int sampleX = Math.min(resolution - 1, x * resolution / width);
                int sampleY = Math.min(resolution - 1, y * resolution / height);
                int sample = sampleY * resolution + sampleX;
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
            int offset = sample * 4;
            expected[offset] *= inverseCount;
            expected[offset + 1] *= inverseCount;
            expected[offset + 2] *= inverseCount;
            expected[offset + 3] *= inverseCount;
        }

        EmissionFootprint footprint = builder.build();
        assertNotNull(footprint);
        assertEquals(resolution, footprint.resolution());
        assertEquals(resolution * resolution, footprint.sampleCount());
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
            int expectedIndex = Math.max(0, Math.min(resolution - 1, (int) (coordinate * resolution)));
            assertEquals(expectedIndex, footprint.sampleIndex(coordinate));
        }
    }

    @Test
    void footprintOwnsItsSamplesAndValidatesCardinality() {
        float[] samples = {0.25f, 0.5f, 0.75f, 1.0f};
        EmissionFootprint footprint = new EmissionFootprint(1, samples);
        samples[0] = 9.0f;
        assertEquals(0.25f, footprint.r(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new EmissionFootprint(0, new float[0]));
        assertThrows(IllegalArgumentException.class, () -> new EmissionFootprint(2, new float[15]));
    }

    @Test
    void builderOmitsFootprintsWithoutCoverage() {
        EmissionFootprint.Builder builder = new EmissionFootprint.Builder(4, 2, 2);
        builder.add(0, 0, 1.0f, 1.0f, 1.0f, 0.0f);
        assertNull(builder.build());
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
