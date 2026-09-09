package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import org.junit.jupiter.api.Test;
import java.util.AbstractList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtNeeAtPlanTest {
    private static final LightDescriptor.Distant LIGHT = new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, .4, false);

    @Test
    void remapsExactIdentitiesAgainstAnySubmittedPredecessor() {
        var first = new RtNeeAtPlan.Cache().prepare(lights(11, 22, 33), 1);
        var skipped = new RtNeeAtPlan.Cache().prepare(lights(44, 22, 11), 1);
        var current = new RtNeeAtPlan.Cache().prepare(lights(33, 44, 11), 1);

        assertEquals(2, remap(current, first, 0));
        assertEquals(-1, remap(current, first, 1));
        assertEquals(0, remap(current, first, 2));
        assertEquals(1, remap(current, skipped, 0));
        assertEquals(0, remap(current, current, 0));
        assertEquals(2, remap(current, current, 2));
        assertEquals(0, lookup(first, 11));
    }

    @Test
    void collisionProbesCompareAllIdentityBitsAndClearEmptySlots() {
        long first = 1, second = 0x0000_0001_0000_0000L;
        assertEquals(RtNeeAtPlan.hash(first), RtNeeAtPlan.hash(second));
        var plan = new RtNeeAtPlan.Cache().prepare(lights(first, second), 1);

        assertEquals(0, lookup(plan, first));
        assertEquals(1, lookup(plan, second));
        assertEquals(-1, lookup(plan, 0));
        assertEquals(-1, lookup(plan, 0xffff_ffff_ffff_ffffL));
        var bytes = plan.packLookup();
        assertEquals(64, bytes.remaining());
        int populated = 0;
        for (int slot = 0; slot <= plan.mask(); slot++) {
            if (bytes.getLong(slot * 16) != 0) populated++;
            assertEquals(0, bytes.getInt(slot * 16 + 12));
        }
        assertEquals(2, populated);
        assertEquals(second, plan.packIdentities().getLong(8));
        assertEquals(RtNeeAtPlan.samplingPower(LIGHT, 1), plan.packPower().getFloat(4));
        assertEquals(-1, lookup(new RtNeeAtPlan.Cache().prepare(List.of(), 1), 1));
    }

    @Test
    void unchangedSourcePagesSharePlansWhileReorderingPreservesPowerAndIdentity() {
        var first = new CountingPage(lights(10, 20));
        var second = new CountingPage(lights(30));
        var cache = new RtNeeAtPlan.Cache();
        var initial = cache.prepare(SnapshotList.ofPages(List.of(first, second)), 1);
        assertSame(initial, cache.prepare(SnapshotList.ofPages(List.of(first, second)), 1));
        first.reads = second.reads = 0;

        var reordered = cache.prepare(SnapshotList.ofPages(List.of(second, first)), 1);

        assertEquals(0, first.reads + second.reads);
        assertEquals(1, remap(reordered, initial, 0));
        assertEquals(2, remap(reordered, initial, 1));
        assertEquals(0, remap(reordered, initial, 2));
        assertEquals(initial.packPower(), reordered.packPower());
        assertSame(reordered, cache.prepare(SnapshotList.ofPages(List.of(second, first)), 1));
    }

    @Test
    void changedPageDoesNotReadUnchangedLightsOrMutateEarlierRevision() {
        var shared = new CountingPage(lights(10, 20));
        var cache = new RtNeeAtPlan.Cache();
        var old = cache.prepare(SnapshotList.ofPages(List.of(shared, lights(30))), 1);
        shared.reads = 0;

        var current = cache.prepare(SnapshotList.ofPages(List.of(shared, lights(40))), 1);

        assertEquals(0, shared.reads);
        assertEquals(-1, remap(current, old, 2));
        assertEquals(30, old.packIdentities().getLong(2 * Long.BYTES));
        assertEquals(40, current.packIdentities().getLong(2 * Long.BYTES));
        assertEquals(old.packPower(), current.packPower());
    }

    @Test
    void rejectedPlanDoesNotPublishItsCacheKey() {
        var cache = new RtNeeAtPlan.Cache();
        var valid = lights(10);
        var initial = cache.prepare(valid, 1);
        var invalid = lights(0);

        assertThrows(IllegalArgumentException.class, () -> cache.prepare(invalid, 1));
        assertThrows(IllegalArgumentException.class, () -> cache.prepare(invalid, 1));
        assertSame(initial, cache.prepare(valid, 1));
    }

    @Test
    void convertsAreaToSquareMetersAndUsesCircularSpotSolidAngle() {
        var area = new LightDescriptor.Parallelogram(0, 0, 0, 2, 0, 0, 0, 3, 0, 4, 5, 6);
        var cache = new RtNeeAtPlan.Cache();
        var lights = List.of(new RtRetainedSceneBackend.SceneLight(1, area));
        var initial = cache.prepare(lights, 1);
        var scaled = cache.prepare(lights, 2);
        assertEquals(initial.packPower().getFloat(0) * 4, scaled.packPower().getFloat(0));
        assertEquals(0, remap(scaled, initial, 0));
        var spot = new LightDescriptor.Spot(0, 0, 0, 0, 0, 1, 10, .3, 1, 1, 1);
        assertEquals(2 * Math.PI * (1 - Math.cos(.3)), RtNeeAtPlan.samplingPower(spot, 1), 1e-6);
        assertTrue(RtNeeAtPlan.samplingPower(LIGHT, 1) > 0);
        assertEquals(1, RtNeeAtPlan.DISTANT_REFERENCE_AREA_M2);
    }

    @Test
    void totalPowerAccumulatesSmallLightsInDoublePrecision() {
        float[] power = new float[100_000];
        power[0] = 1e8f;
        java.util.Arrays.fill(power, 1, power.length, 1);
        assertEquals(100_099_999.0, RtNeeAtPlan.powerTotal(power), 8.0);
        assertEquals(0, RtNeeAtPlan.powerTotal(new float[0]));
        assertThrows(IllegalArgumentException.class, () -> new RtNeeAtPlan.Cache().prepare(lights(1), Double.NaN));
    }

    private static int remap(RtNeeAtPlan.Plan current, RtNeeAtPlan.Plan previous, int index) {
        return lookup(current, previous.packIdentities().getLong(index * Long.BYTES));
    }

    /** Reads the lookup buffer with the shader's probing and empty-slot rules. */
    private static int lookup(RtNeeAtPlan.Plan plan, long identity) {
        var bytes = plan.packLookup();
        int slot = RtNeeAtPlan.hash(identity) & plan.mask();
        for (int probes = 0; probes <= plan.mask(); probes++) {
            int offset = slot * 16;
            long candidate = bytes.getLong(offset);
            if (candidate == 0) return -1;
            if (candidate == identity) return bytes.getInt(offset + Long.BYTES);
            slot = (slot + 1) & plan.mask();
        }
        throw new AssertionError("Lookup table has no empty slot");
    }

    private static List<RtRetainedSceneBackend.SceneLight> lights(long... identities) {
        return java.util.Arrays.stream(identities).mapToObj(id -> new RtRetainedSceneBackend.SceneLight(id, LIGHT)).toList();
    }

    private static final class CountingPage extends AbstractList<RtRetainedSceneBackend.SceneLight> {
        private final List<RtRetainedSceneBackend.SceneLight> lights;
        int reads;
        CountingPage(List<RtRetainedSceneBackend.SceneLight> lights) { this.lights = lights; }
        @Override public RtRetainedSceneBackend.SceneLight get(int index) { reads++; return lights.get(index); }
        @Override public int size() { return lights.size(); }
    }
}
