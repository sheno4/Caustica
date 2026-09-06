package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class RtNeeAtPlanTest {
    @Test
    void remapsStableIdentitiesAcrossReorderRemovalAndInsertion() {
        var spot = new LightDescriptor.Spot(0, 0, 0, 0, 0, 1, 1, 0.2, 1, 1, 10);
        var previous = List.of(light(11, spot), light(22, spot), light(33, spot));
        var current = List.of(light(33, spot), light(44, spot), light(11, spot));

        RtNeeAtPlan.Plan plan = RtNeeAtPlan.build(current, previous, 1.0);

        assertArrayEquals(new int[]{2, RtNeeAtPlan.NO_LIGHT, 0}, plan.previousToCurrent());
        var packed = plan.pack().order(ByteOrder.nativeOrder());
        assertEquals(2, packed.getInt(0));
        assertTrue(packed.getFloat(4) > 0.0f);
        assertEquals(RtNeeAtPlan.NO_LIGHT, packed.getInt(8));
    }

    @Test
    void assignsPositivePhysicalPowerToEveryPublicShape() {
        List<LightDescriptor> descriptors = List.of(
                new LightDescriptor.Parallelogram(0, 0, 0, 2, 0, 0, 0, 3, 0, 4, 5, 6),
                new LightDescriptor.Spot(0, 0, 0, 0, 0, 1,
                        10, 0.2, 11, 12, 13),
                new LightDescriptor.Distant(0, 1, 0, 14, 15, 16, 0.4, false));

        descriptors.forEach(descriptor -> assertTrue(RtNeeAtPlan.samplingPower(descriptor, 1.0) > 0.0f));
    }

    @Test
    void convertsParallelogramAreaToSquareMetersAndUsesCircularSpotSolidAngle() {
        var parallelogram = new LightDescriptor.Parallelogram(0, 0, 0,
                2, 0, 0, 0, 3, 0, 4, 5, 6);
        float unitScale = RtNeeAtPlan.samplingPower(parallelogram, 1.0);
        assertEquals(unitScale * 4.0f, RtNeeAtPlan.samplingPower(parallelogram, 2.0), unitScale * 1.0e-5f);

        double halfAngle = 0.3;
        var circularSpot = new LightDescriptor.Spot(0, 0, 0, 0, 0, 1,
                10, halfAngle, 1, 1, 1);
        double expectedSolidAngle = 2.0 * Math.PI * (1.0 - Math.cos(halfAngle));
        assertEquals(expectedSolidAngle, RtNeeAtPlan.samplingPower(circularSpot, 1.0), 1.0e-6);
        assertEquals(1.0, RtNeeAtPlan.DISTANT_REFERENCE_AREA_M2);
    }

    @Test
    void powerTotalAccumulatesInDoublePrecision() {
        // Naive float accumulation stalls once the running sum dwarfs each addend, which would make
        // the GPU's power-based prior sum to well under one across a large light set.
        float[] power = new float[100_000];
        power[0] = 1.0e8f;
        java.util.Arrays.fill(power, 1, power.length, 1.0f);

        float naive = 0.0f;
        for (float value : power) naive += value;

        // At 1e8 a float step is 8, so every unit addend rounds away and the naive sum never moves.
        assertEquals(1.0e8f, naive);
        assertEquals(100_099_999.0, RtNeeAtPlan.powerTotal(power), 8.0);
        assertEquals(0.0f, RtNeeAtPlan.powerTotal(new float[0]));
    }

    @Test
    void descriptorRejectsRadiometryThatCannotRemainFiniteOnGpu() {
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Spot(0, 0, 0, 0, 0, 1,
                1, 0.2, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    void unchangedRevisionReusesStablePlanAndPackedStorageAcrossHistoryResets() {
        var descriptor = new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, 0.4, false);
        var lights = List.of(light(10, descriptor), light(20, descriptor));
        var cache = new RtNeeAtPlan.Cache();
        var reset = cache.prepare(lights, false, 1.0);
        var stable = cache.prepare(lights, true, 1.0);

        assertArrayEquals(new int[0], reset.previousToCurrent());
        assertArrayEquals(new int[]{0, 1}, stable.previousToCurrent());
        assertSame(stable, cache.prepare(lights, true, 1.0));
        assertSame(stable.pack(), cache.prepare(lights, true, 1.0).pack());
        assertSame(reset, cache.prepare(lights, false, 1.0));
        assertSame(stable, cache.prepare(lights, true, 1.0));
        assertEquals(RtNeeAtPlan.powerTotal(stable.power()), stable.powerTotal());
    }

    @Test
    void membershipTransitionRemapsOnlyTheFirstFrameAndPreservesOldPlans() {
        var descriptor = new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, 0.4, false);
        var previous = List.of(light(10, descriptor), light(20, descriptor), light(30, descriptor));
        var current = List.of(light(30, descriptor), light(10, descriptor));
        var cache = new RtNeeAtPlan.Cache();
        cache.prepare(previous, false, 1.0);
        var oldStable = cache.prepare(previous, true, 1.0);
        var transition = cache.prepare(current, true, 1.0);
        var stable = cache.prepare(current, true, 1.0);

        assertArrayEquals(new int[]{1, RtNeeAtPlan.NO_LIGHT, 0}, transition.previousToCurrent());
        assertArrayEquals(new int[]{0, 1}, stable.previousToCurrent());
        assertArrayEquals(new int[]{0, 1, 2}, oldStable.previousToCurrent());
        assertSame(stable, cache.prepare(current, true, 1.0));
        assertNotSame(transition, stable);
        assertArrayEquals(new int[0], cache.prepare(List.of(), false, 1.0).previousToCurrent());
        assertArrayEquals(new float[0], cache.prepare(List.of(), true, 1.0).power());
    }

    @Test
    void descriptorAndScaleChangesRefreshPowerWithoutChangingMembershipRemap() {
        var descriptor = new LightDescriptor.Parallelogram(0, 0, 0, 2, 0, 0, 0, 3, 0, 4, 5, 6);
        var brighter = new LightDescriptor.Parallelogram(0, 0, 0, 2, 0, 0, 0, 3, 0, 8, 10, 12);
        var cache = new RtNeeAtPlan.Cache();
        var initial = List.of(light(10, descriptor), light(20, descriptor));
        cache.prepare(initial, false, 1.0);
        var oldStable = cache.prepare(initial, true, 1.0);
        var updated = List.of(light(10, descriptor), light(20, brighter));
        var changed = cache.prepare(updated, true, 1.0);
        var scaled = cache.prepare(updated, true, 2.0);

        assertSame(oldStable.previousToCurrent(), changed.previousToCurrent());
        assertSame(changed.previousToCurrent(), scaled.previousToCurrent());
        assertEquals(oldStable.power()[0], changed.power()[0]);
        assertEquals(oldStable.power()[1] * 2.0f, changed.power()[1]);
        assertEquals(changed.power()[0] * 4.0f, scaled.power()[0]);
        assertEquals(changed.power()[1] * 4.0f, scaled.power()[1]);
        assertEquals(oldStable.power()[0], oldStable.power()[1]);
    }

    @Test
    void changedPageDoesNotEnumerateUnchangedLights() {
        var descriptor = new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, 0.4, false);
        var shared = new CountingPage(List.of(light(10, descriptor), light(20, descriptor)));
        var initial = SnapshotList.ofPages(List.of(shared, List.of(light(30, descriptor))));
        var cache = new RtNeeAtPlan.Cache();
        cache.prepare(initial, false, 1.0);
        var initialPower = cache.prepare(initial, true, 1.0).power().clone();
        shared.reads = 0;
        var current = SnapshotList.ofPages(List.of(shared, List.of(light(40, descriptor))));
        var transition = cache.prepare(current, true, 1.0);

        assertEquals(0, shared.reads);
        assertArrayEquals(new int[]{0, 1, RtNeeAtPlan.NO_LIGHT}, transition.previousToCurrent());
        assertArrayEquals(initialPower, transition.power());
    }

    @Test
    void reorderedSharedPagesRemapWithoutEnumeratingTheirLights() {
        var descriptor = new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, 0.4, false);
        var first = new CountingPage(List.of(light(10, descriptor), light(20, descriptor)));
        var second = new CountingPage(List.of(light(30, descriptor)));
        var cache = new RtNeeAtPlan.Cache();
        cache.prepare(SnapshotList.ofPages(List.of(first, second)), false, 1.0);
        first.reads = second.reads = 0;
        var current = SnapshotList.ofPages(List.of(second, first));
        var transition = cache.prepare(current, true, 1.0);

        assertArrayEquals(new int[]{1, 2, 0}, transition.previousToCurrent());
        assertEquals(0, first.reads + second.reads);
        assertArrayEquals(new int[]{0, 1, 2}, cache.prepare(current, true, 1.0).previousToCurrent());
    }

    @Test
    void repartitionedPagesPreserveIdentityAndPowerAcrossRemovalAndInsertion() {
        var dim = new LightDescriptor.Distant(0, 1, 0, 1, 1, 1, 0.4, false);
        var bright = new LightDescriptor.Distant(0, 1, 0, 5, 5, 5, 0.4, false);
        var cache = new RtNeeAtPlan.Cache();
        var initial = SnapshotList.ofPages(List.of(List.of(light(10, dim), light(20, bright)), List.of(light(30, dim))));
        var old = cache.prepare(initial, false, 1.0);
        var current = SnapshotList.ofPages(List.of(List.of(light(30, dim), light(40, bright)), List.of(light(20, bright))));
        var transition = cache.prepare(current, true, 1.0);

        assertArrayEquals(new int[]{RtNeeAtPlan.NO_LIGHT, 2, 0}, transition.previousToCurrent());
        assertArrayEquals(new float[]{old.power()[2], old.power()[1], old.power()[1]}, transition.power());
        assertArrayEquals(new float[]{1, 5, 1}, old.power(), 1.0e-5f);
    }

    @Test
    void samePageRevisionReusesPlansAndScaleChangeRefreshesPower() {
        var descriptor = new LightDescriptor.Parallelogram(0, 0, 0, 2, 0, 0, 0, 3, 0, 4, 5, 6);
        var page = List.of(light(10, descriptor), light(20, descriptor));
        var cache = new RtNeeAtPlan.Cache();
        cache.prepare(SnapshotList.ofPages(List.of(page)), false, 1.0);
        var stable = cache.prepare(SnapshotList.ofPages(List.of(page)), true, 1.0);
        assertSame(stable, cache.prepare(SnapshotList.ofPages(List.of(page)), true, 1.0));
        var scaled = cache.prepare(SnapshotList.ofPages(List.of(page)), true, 2.0);
        assertSame(stable.previousToCurrent(), scaled.previousToCurrent());
        assertEquals(stable.power()[0] * 4.0f, scaled.power()[0]);
    }

    private static final class CountingPage extends AbstractList<RtRetainedSceneBackend.SceneLight> {
        private final List<RtRetainedSceneBackend.SceneLight> lights;
        int reads;

        private CountingPage(List<RtRetainedSceneBackend.SceneLight> lights) { this.lights = lights; }
        @Override public RtRetainedSceneBackend.SceneLight get(int index) { reads++; return lights.get(index); }
        @Override public int size() { return lights.size(); }
    }

    private static RtRetainedSceneBackend.SceneLight light(long identity, LightDescriptor descriptor) {
        return new RtRetainedSceneBackend.SceneLight(identity, descriptor);
    }
}
