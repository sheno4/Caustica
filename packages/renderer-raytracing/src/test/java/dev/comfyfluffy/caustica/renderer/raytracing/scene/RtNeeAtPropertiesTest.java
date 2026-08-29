package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtNeeAtPropertiesTest {
    private static final int TILE_SIZE = 8;
    private static final int LOCAL_SLOTS = 128;
    private static final int NO_LIGHT = 0xffff_ffff;

    @Test
    void globalBakeAlwaysProducesANormalizedMonotoneCdf() {
        Random random = new Random(0x4e45452d41544cL);
        for (int lightCount : new int[]{1, 2, 3, 63, 64, 65, 127, 128, 129, 257}) {
            float[] power = new float[lightCount];
            int[] currentToPrevious = new int[lightCount];
            int[] previousFeedback = new int[lightCount + 7];
            for (int index = 0; index < lightCount; index++) {
                power[index] = Math.scalb(0.5f + random.nextFloat(), random.nextInt(-8, 9));
                currentToPrevious[index] = index % 5 == 0 ? NO_LIGHT : index;
                previousFeedback[index] = random.nextInt(0, 1_000_000);
            }

            GlobalDistribution distribution = bakeGlobal(power, currentToPrevious,
                    previousFeedback, true);

            double pdfTotal = 0.0;
            float previousCdf = 0.0f;
            for (int index = 0; index < lightCount; index++) {
                assertTrue(Float.isFinite(distribution.pdf[index]));
                assertTrue(distribution.pdf[index] >= 0.0f);
                assertTrue(distribution.cdf[index] >= previousCdf);
                assertTrue(distribution.cdf[index] <= 1.00001f);
                pdfTotal += distribution.pdf[index];
                previousCdf = distribution.cdf[index];
            }
            assertEquals(1.0, pdfTotal, 2.0e-5);
            assertEquals(1.0f, distribution.cdf[lightCount - 1]);
        }
    }

    @Test
    void globalBinarySearchOwnsExactCdfBoundaryOnTheLowerEntry() {
        float[] cdf = {0.2f, 0.5f, 1.0f};

        assertEquals(0, sampleGlobal(cdf, 0.0f));
        assertEquals(0, sampleGlobal(cdf, 0.2f));
        assertEquals(1, sampleGlobal(cdf, Math.nextUp(0.2f)));
        assertEquals(1, sampleGlobal(cdf, 0.5f));
        assertEquals(2, sampleGlobal(cdf, Math.nextUp(0.5f)));
        assertEquals(2, sampleGlobal(cdf, 1.0f));
    }

    @Test
    void localBinarySearchSkipsCdfPlateausAndRejectsTheOpenUpperEdge() {
        int[] lights = {11, NO_LIGHT, 22, NO_LIGHT};
        long[] cdf = {3, 3, 10, 10};

        assertEquals(11, sampleLocal(lights, cdf, 0.0f));
        assertEquals(22, sampleLocal(lights, cdf, 0.3f));
        assertEquals(22, sampleLocal(lights, cdf, Math.nextDown(1.0f)));
        assertEquals(NO_LIGHT, sampleLocal(lights, cdf, 1.0f));
        assertEquals(NO_LIGHT, sampleLocal(lights, new long[4], 0.5f));
    }

    @Test
    void localHashProbingAndTileAddressingStayInsideAllocatedRanges() {
        for (int light = 0; light < 10_000; light++) {
            int slot = Integer.remainderUnsigned(hash(light), LOCAL_SLOTS);
            assertTrue(slot >= 0 && slot < LOCAL_SLOTS);
        }
        for (int width : new int[]{1, 7, 8, 9, 63, 64, 65, 1919, 1920}) {
            for (int height : new int[]{1, 8, 9, 1079, 1080}) {
                int tileCountX = divideRoundUp(width, TILE_SIZE);
                int tileCountY = divideRoundUp(height, TILE_SIZE);
                long entryCount = (long) tileCountX * tileCountY * LOCAL_SLOTS;
                for (int y : new int[]{0, height - 1, height, Integer.MAX_VALUE}) {
                    for (int x : new int[]{0, width - 1, width, Integer.MAX_VALUE}) {
                        long base = tileBase(x, y, tileCountX, tileCountY);
                        assertTrue(base >= 0);
                        assertTrue(base + LOCAL_SLOTS - 1 < entryCount);
                    }
                }
            }
        }

        int[] collidingLights = collidingLights(4);
        LocalDistribution table = buildLocal(collidingLights, new int[]{2, 3, 5, 7});
        long total = table.cdf[LOCAL_SLOTS - 1];
        assertEquals(17, total);
        assertEquals(1.0, Arrays.stream(collidingLights)
                .mapToDouble(light -> localPdf(table, light)).sum(), 1.0e-12);
    }

    @Test
    void historyRequiresIdentityContinuityWithoutResetResizeOrFrameGap() {
        var next = new RtNeeAtBackend.FrameInput(1920, 1080, 42, 1.0f, true);

        assertTrue(RtNeeAtBackend.historyValid(next, true, 41, 1920, 1080));
        assertFalse(RtNeeAtBackend.historyValid(next, false, 41, 1920, 1080));
        assertFalse(RtNeeAtBackend.historyValid(next, true, 40, 1920, 1080));
        assertFalse(RtNeeAtBackend.historyValid(next, true, 41, 1280, 1080));
        assertFalse(RtNeeAtBackend.historyValid(next, true, 41, 1920, 720));
        assertFalse(RtNeeAtBackend.historyValid(
                new RtNeeAtBackend.FrameInput(1920, 1080, 42, 1.0f, false),
                true, 41, 1920, 1080));
    }

    @Test
    void cpuReferenceIsPinnedToSlangConstantsAndBranchEdges() throws IOException {
        String lights = shader("retained_lights.slang");
        String bake = shader("nee_at_bake.slang");

        assertEquals(TILE_SIZE, RtNeeAtBackend.TILE_SIZE);
        assertEquals(LOCAL_SLOTS, RtNeeAtBackend.LOCAL_SLOTS);
        assertEquals(NO_LIGHT, RtNeeAtPlan.NO_LIGHT);
        assertTrue(lights.contains("NEE_AT_NO_LIGHT = 0xffffffffu"));
        assertTrue(lights.contains("if (randomValue <= entries[middle].y) high = middle"));
        assertTrue(lights.contains("if (float(entries[base + middle].y) > target) high = middle"));
        assertTrue(lights.contains("uint base = neeAtTileIndex(state, pixel) * state.localSlotCount"));
        assertTrue(lights.contains("uint slot = retainedLightHash(lightIndex) % state.localSlotCount"));
        assertTrue(bake.contains("groupshared uint localLights[128]"));
        assertTrue(bake.contains("groupshared uint localMasses[128]"));
        assertTrue(bake.contains("groupshared float globalPowerSums[64]"));
        assertTrue(bake.contains("value *= 0x7feb352du"));
        assertTrue(bake.contains("value *= 0x846ca68bu"));
        assertTrue(bake.contains("index + 1u == state.lightCount ? 1.0"));
        assertTrue(bake.contains("uint base = tileIndex * state.localSlotCount"));
        assertTrue(bake.contains("currentLight = plan[event.x].previousToCurrent"));
    }

    private static GlobalDistribution bakeGlobal(float[] power, int[] currentToPrevious,
                                                   int[] previousFeedback, boolean historyValid) {
        float powerTotal = 0.0f;
        float feedbackTotal = 0.0f;
        for (int index = 0; index < power.length; index++) {
            powerTotal += power[index];
            int previous = currentToPrevious[index];
            if (historyValid && Integer.compareUnsigned(previous, previousFeedback.length) < 0) {
                feedbackTotal += previousFeedback[previous];
            }
        }
        float[] pdf = new float[power.length];
        float[] cdf = new float[power.length];
        float carry = 0.0f;
        for (int index = 0; index < power.length; index++) {
            float prior = power[index] / Math.max(powerTotal, 1.0e-20f);
            float historic = prior;
            int previous = currentToPrevious[index];
            if (historyValid && feedbackTotal > 0.0f) {
                historic = Integer.compareUnsigned(previous, previousFeedback.length) < 0
                        ? previousFeedback[previous] / feedbackTotal : 0.0f;
            }
            pdf[index] = prior + (historic - prior) * 0.75f;
            carry += pdf[index];
            cdf[index] = index + 1 == power.length ? 1.0f : carry;
        }
        return new GlobalDistribution(pdf, cdf);
    }

    private static int sampleGlobal(float[] cdf, float randomValue) {
        int low = 0;
        int high = cdf.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (randomValue <= cdf[middle]) high = middle;
            else low = middle + 1;
        }
        return Math.min(low, cdf.length - 1);
    }

    private static int sampleLocal(int[] lights, long[] cdf, float randomValue) {
        long total = cdf[cdf.length - 1];
        if (total == 0) return NO_LIGHT;
        float target = randomValue * (float) total;
        int low = 0;
        int high = cdf.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if ((float) cdf[middle] > target) high = middle;
            else low = middle + 1;
        }
        return low < lights.length ? lights[low] : NO_LIGHT;
    }

    private static LocalDistribution buildLocal(int[] eventLights, int[] eventMasses) {
        int[] lights = new int[LOCAL_SLOTS];
        Arrays.fill(lights, NO_LIGHT);
        long[] masses = new long[LOCAL_SLOTS];
        for (int event = 0; event < eventLights.length; event++) {
            int light = eventLights[event];
            int slot = Integer.remainderUnsigned(hash(light), LOCAL_SLOTS);
            for (int probe = 0; probe < LOCAL_SLOTS; probe++) {
                if (lights[slot] == NO_LIGHT || lights[slot] == light) {
                    lights[slot] = light;
                    masses[slot] += eventMasses[event];
                    break;
                }
                slot = (slot + 1) % LOCAL_SLOTS;
            }
        }
        for (int slot = 1; slot < LOCAL_SLOTS; slot++) masses[slot] += masses[slot - 1];
        return new LocalDistribution(lights, masses);
    }

    private static double localPdf(LocalDistribution table, int light) {
        long total = table.cdf[LOCAL_SLOTS - 1];
        int slot = Integer.remainderUnsigned(hash(light), LOCAL_SLOTS);
        for (int probe = 0; probe < LOCAL_SLOTS; probe++) {
            if (table.lights[slot] == light) {
                long previous = slot == 0 ? 0 : table.cdf[slot - 1];
                return (double) (table.cdf[slot] - previous) / total;
            }
            if (table.lights[slot] == NO_LIGHT) return 0.0;
            slot = (slot + 1) % LOCAL_SLOTS;
        }
        return 0.0;
    }

    private static long tileBase(int pixelX, int pixelY, int tileCountX, int tileCountY) {
        int tileX = Math.min(Integer.divideUnsigned(pixelX, TILE_SIZE), tileCountX - 1);
        int tileY = Math.min(Integer.divideUnsigned(pixelY, TILE_SIZE), tileCountY - 1);
        return ((long) tileY * tileCountX + tileX) * LOCAL_SLOTS;
    }

    private static int hash(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static int[] collidingLights(int count) {
        int[] result = new int[count];
        int found = 0;
        int wantedSlot = Integer.remainderUnsigned(hash(0), LOCAL_SLOTS);
        for (int light = 0; found < count; light++) {
            if (Integer.remainderUnsigned(hash(light), LOCAL_SLOTS) == wantedSlot) {
                result[found++] = light;
            }
        }
        return result;
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static String shader(String name) throws IOException {
        try (var input = RtNeeAtPropertiesTest.class.getResourceAsStream(
                "/caustica/shaders/world/" + name)) {
            if (input == null) throw new IllegalStateException("missing shader " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record GlobalDistribution(float[] pdf, float[] cdf) { }
    private record LocalDistribution(int[] lights, long[] cdf) { }
}
