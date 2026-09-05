package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtNeeAtPropertiesTest {
    private static final int TILE_SIZE = 8;
    private static final int LOCAL_SLOTS = 128;
    private static final int PROXY_RATIO = 12;
    private static final float LOCAL_TO_GLOBAL_RATIO = 0.65f;
    private static final float GLOBAL_FEEDBACK_WEIGHT = 0.75f;
    private static final int NO_LIGHT = 0xffff_ffff;

    @Test
    void proxyCountsStayInBudgetKeepEveryLightReachableAndTrackTheBlendedPdf() {
        Random random = new Random(0x4e45452d41544cL);
        for (int lightCount : new int[]{1, 2, 3, 63, 64, 65, 127, 128, 129, 257}) {
            float[] power = new float[lightCount];
            int[] feedback = new int[lightCount];
            int contributingPixels = 0;
            for (int index = 0; index < lightCount; index++) {
                power[index] = Math.scalb(0.5f + random.nextFloat(), random.nextInt(-8, 9));
                feedback[index] = random.nextInt(0, 4096);
                contributingPixels += feedback[index];
            }

            GlobalDistribution distribution = bakeGlobal(power, feedback, contributingPixels);

            // The proxy buffer is sized for exactly this budget, so exceeding it would corrupt memory.
            assertTrue(distribution.total <= (long) lightCount * PROXY_RATIO);
            double pdfTotal = 0.0;
            int expectedBase = 0;
            for (int index = 0; index < lightCount; index++) {
                // Bases must strictly increase for the fill pass's binary search to find the owner.
                assertTrue(distribution.count[index] >= 1);
                assertEquals(expectedBase, distribution.base[index]);
                expectedBase += distribution.count[index];
                pdfTotal += (double) distribution.count[index] / distribution.total;
            }
            assertEquals(distribution.total, expectedBase);
            // Proxy counts are the pdf numerator, so the distribution normalizes by construction.
            assertEquals(1.0, pdfTotal, 1.0e-9);
        }
    }

    @Test
    void everyProxyResolvesToTheLightThatOwnsIt() {
        float[] power = {8.0f, 0.25f, 1.0f, 4.0f, 0.5f};
        GlobalDistribution distribution = bakeGlobal(power, new int[power.length], 0);

        for (int proxy = 0; proxy < distribution.total; proxy++) {
            int owner = proxyOwner(distribution.base, power.length, proxy);
            assertTrue(proxy >= distribution.base[owner]);
            assertTrue(proxy < distribution.base[owner] + distribution.count[owner]);
        }
    }

    @Test
    void candidateSplitAlwaysKeepsAtLeastOneGlobalDraw() {
        assertEquals(5, localCandidateCount(LOCAL_TO_GLOBAL_RATIO, 8));
        assertEquals(0, localCandidateCount(0.0f, 8));
        assertEquals(7, localCandidateCount(1.0f, 8));
        assertEquals(0, localCandidateCount(1.0f, 1));
        for (int candidates = 1; candidates <= 8; candidates++) {
            for (float ratio : new float[]{0.0f, 0.25f, LOCAL_TO_GLOBAL_RATIO, 0.95f, 1.0f}) {
                int local = localCandidateCount(ratio, candidates);
                assertTrue(local >= 0 && local < candidates);
            }
        }
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
                        for (int frame : new int[]{0, 1, 42, Integer.MAX_VALUE}) {
                            long base = tileBase(x, y, frame, tileCountX, tileCountY);
                            assertTrue(base >= 0);
                            assertTrue(base + LOCAL_SLOTS - 1 < entryCount);
                        }
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
    void jitteredTileLookupAndBakeAddressingAreInverse() {
        for (int width = 1; width <= 17; width++) {
            int paddedWidth = divideRoundUp(width, TILE_SIZE) * TILE_SIZE;
            for (int frame : new int[]{0, 1, 42, Integer.MAX_VALUE}) {
                int jitter = Integer.remainderUnsigned(hash(frame * 2), TILE_SIZE);
                for (int pixel = 0; pixel < width; pixel++) {
                    int shifted = (pixel + jitter) % paddedWidth;
                    int tile = shifted / TILE_SIZE;
                    int local = shifted % TILE_SIZE;
                    int bakedPixel = (tile * TILE_SIZE + local + paddedWidth - jitter) % paddedWidth;
                    assertEquals(pixel, bakedPixel);
                }
            }
        }
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
    void telemetryCountsEveryRetainedDescriptorShape() {
        List<RtRetainedSceneBackend.SceneLight> lights = List.of(
                light(1, new LightDescriptor.Parallelogram(
                        0, 0, 0, 1, 0, 0, 0, 1, 0, 4, 5, 6)),
                light(2, new LightDescriptor.Spot(
                        0, 0, 0, 0, 0, 1, 10, 0.5, 7, 8, 9)),
                light(3, new LightDescriptor.Distant(
                        0, 1, 0, 10, 11, 12, 0.01, true)));

        RtNeeAtBackend.Telemetry telemetry = RtNeeAtBackend.telemetry(lights, true);

        assertEquals(RtNeeAtBackend.CANDIDATES, telemetry.candidates());
        assertTrue(telemetry.historyValid());
        assertEquals(1, telemetry.parallelograms());
        assertEquals(1, telemetry.spots());
        assertEquals(1, telemetry.distants());
        assertEquals(3, telemetry.lightCount());
    }

    private static GlobalDistribution bakeGlobal(float[] power, int[] feedback,
                                                 int contributingPixels) {
        int lightCount = power.length;
        float powerTotal = RtNeeAtPlan.powerTotal(power);
        float feedbackTotal = contributingPixels;
        float budget = (float) (lightCount * (PROXY_RATIO - 2));
        int[] count = new int[lightCount];
        int[] base = new int[lightCount];
        int running = 0;
        for (int index = 0; index < lightCount; index++) {
            float prior = power[index] / Math.max(powerTotal, 1.0e-20f);
            float historic = feedbackTotal > 0.0f ? feedback[index] / feedbackTotal : prior;
            float pdf = Math.clamp(prior + (historic - prior) * GLOBAL_FEEDBACK_WEIGHT, 0.0f, 1.0f);
            count[index] = Math.max(1, (int) Math.ceil(budget * pdf));
            base[index] = running;
            running += count[index];
        }
        return new GlobalDistribution(count, base, running);
    }

    private static int proxyOwner(int[] base, int lightCount, int proxy) {
        int low = 0;
        int high = lightCount - 1;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (base[middle] <= proxy) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private static int localCandidateCount(float ratio, int candidateCount) {
        return Math.min((int) ((candidateCount - 1) * Math.clamp(ratio, 0.0f, 1.0f) + 0.75f),
                candidateCount - 1);
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

    private static long tileBase(int pixelX, int pixelY, int frameIndex, int tileCountX, int tileCountY) {
        int jitterX = Integer.remainderUnsigned(hash(frameIndex * 2), TILE_SIZE);
        int jitterY = Integer.remainderUnsigned(hash(frameIndex * 2 + 1), TILE_SIZE);
        int tileX = (int) (((Integer.toUnsignedLong(pixelX) + jitterX)
                % ((long) tileCountX * TILE_SIZE)) / TILE_SIZE);
        int tileY = (int) (((Integer.toUnsignedLong(pixelY) + jitterY)
                % ((long) tileCountY * TILE_SIZE)) / TILE_SIZE);
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

    private static RtRetainedSceneBackend.SceneLight light(long identity, LightDescriptor descriptor) {
        return new RtRetainedSceneBackend.SceneLight(identity, descriptor);
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private record GlobalDistribution(int[] count, int[] base, int total) { }
    private record LocalDistribution(int[] lights, long[] cdf) { }
}
