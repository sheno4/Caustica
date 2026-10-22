package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyShadowStateTest {
    private final CloudlyShadowState.SourceSettings source = new CloudlyShadowState.SourceSettings(
            6000, 0, 20000, 100, true, true, 8,
            new CloudlyShadowState.Extent(1024, 1024, 128), new CloudlyShadowState.Extent(1024, 512, 128),
            0.1f, 0.15f, 1.5f, 0.5f, true, 0.5f, 4, 2, 2);
    private final CloudlyShadowState.HostChoices choices = new CloudlyShadowState.HostChoices(
            true, false, true, true, true, false, true, true);
    private final CloudlyShadowState.CameraCentimeters camera = new CloudlyShadowState.CameraCentimeters(10000, 20000, 30000);
    private final CloudlyShadowState.LightAngles light = new CloudlyShadowState.LightAngles(25, 25, 135);

    @Test void horizonAndStrictSwitchBoundaryPreserveFiniteSourceGeometry() {
        var horizon = geometry(0), justLow = geometry(7.999f), boundary = geometry(8), negative = geometry(-8);
        assertTrue(horizon.lowElevation());
        assertEquals(200000f, horizon.slabDistance());
        assertEquals(1f / 200000f, horizon.reciprocalSlabDistance());
        assertTrue(justLow.lowElevation());
        assertFalse(boundary.lowElevation());
        assertFalse(negative.lowElevation());
        assertEquals(boundary.slabDistance(), negative.slabDistance());
        assertTrue(Float.isFinite(horizon.slabDistance()));
        assertTrue(Float.isFinite(boundary.slabDistance()));
        assertEquals(16, horizon.flags());
        assertEquals(0, boundary.flags());
        assertEquals(0, horizon.lightDirection().x() * horizon.xAxis().x()
                + horizon.lightDirection().y() * horizon.xAxis().y()
                + horizon.lightDirection().z() * horizon.xAxis().z(), 0.000001);
    }

    @Test void forcedStartupProducesBothCascadesAndPreservesRecordOwnership() {
        var first = cache("L0"), second = cache("L1");
        var geometry = CloudlyShadowState.geometry(source, light, choices);
        assertThrows(IllegalStateException.class, () -> CloudlyShadowState.samplingInputs(geometry, first, second, 100));
        var level0 = CloudlyShadowState.plan(first, source, choices, light, geometry, 0, 100, camera, true, true);
        var level1 = CloudlyShadowState.plan(second, source, choices, light, geometry, 1, 100, camera, true, true);
        assertEquals(List.of("69", "115", "107", "114"), ids(level0));
        assertEquals(List.of("69", "114"), ids(level1));
        assertEquals("L0-work", first.work().resources());
        assertFalse(first.work().completed());
        var completed = level0.stateAfterGpuCompletion();
        assertEquals("L0-next", completed.current().resources());
        assertEquals("L0-work", completed.next().resources());
        assertEquals("L0-current", completed.work().resources());
        assertTrue(completed.next().completed());
        assertEquals(level0.stages().getFirst().inputs().get("nativeFrame.ShadowTracePlaneSpaceCenter")[0],
                completed.next().center().x());
        assertEquals(0, level0.stages().getFirst().inputs().get("frame.shadowTileIndex")[0]);
        assertTrue(level0.publishesRecords());
        assertTrue(level1.publishesRecords());
        var sample = CloudlyShadowState.samplingInputs(geometry, completed, level1.stateAfterGpuCompletion(), 100);
        assertArrayEquals(new double[]{1, 1, 0, 0}, sample.get("nativeFrame.ShadowTexturesForSampling_Blend"));
        assertEquals(2, level0.stages().get(1).inputs().get("RadiusGround")[0]);
        assertEquals(2, level0.stages().get(1).groupsX());
        assertEquals(512, level0.stages().get(1).groupsY());
    }

    @Test void normalLevelZeroDefersTwoFilterFramesAndLevelOneRetainsDummyTiles() {
        var geometry = CloudlyShadowState.geometry(source, light, choices);
        var state = completeCache("L0");
        for (int frame = 200; frame < 204; frame++) {
            var update = CloudlyShadowState.plan(state, source, choices, light, geometry, 0, frame, camera, false, false);
            assertEquals(List.of("69"), ids(update));
            assertFalse(update.publishesRecords());
            state = update.stateAfterGpuCompletion();
        }
        var x = CloudlyShadowState.plan(state, source, choices, light, geometry, 0, 204, camera, false, false);
        assertEquals(List.of("115"), ids(x));
        var y = CloudlyShadowState.plan(x.stateAfterGpuCompletion(), source, choices, light, geometry, 0, 205, camera, false, false);
        assertEquals(List.of("107", "114"), ids(y));
        assertTrue(y.publishesRecords());
        assertEquals("L0-work", y.stateAfterGpuCompletion().next().resources());
        var second = completeCache("L1");
        for (int frame = 300; frame < 308; frame++) {
            var update = CloudlyShadowState.plan(second, source, choices, light, geometry, 1, frame, camera, false, false);
            assertEquals(frame == 307 ? List.of("69", "114") : List.of("69"), ids(update));
            assertEquals(frame >= 304, update.stages().getFirst().allThreadsOutsideRange());
            assertEquals(frame == 307, update.publishesRecords());
            second = update.stateAfterGpuCompletion();
        }
        assertEquals(0.5f, CloudlyShadowState.blend(315, 307, 16));
        assertEquals(1f, CloudlyShadowState.blend(2, -2, 8));
    }

    private CloudlyShadowState.Geometry geometry(float elevation) {
        return CloudlyShadowState.geometry(source, new CloudlyShadowState.LightAngles(25, elevation, 135), choices);
    }

    private static CloudlyShadowState.Cache<String> cache(String prefix) {
        return cache(prefix, false);
    }
    private static CloudlyShadowState.Cache<String> completeCache(String prefix) {
        return cache(prefix, true);
    }
    private static CloudlyShadowState.Cache<String> cache(String prefix, boolean complete) {
        return new CloudlyShadowState.Cache<>(
                new CloudlyShadowState.ResourceRecord<>(new CloudlyShadowState.Vector(1, 2, 3), prefix + "-current", complete),
                new CloudlyShadowState.ResourceRecord<>(new CloudlyShadowState.Vector(4, 5, 6), prefix + "-next", complete),
                new CloudlyShadowState.ResourceRecord<>(new CloudlyShadowState.Vector(7, 8, 9), prefix + "-work", complete),
                512, 2, 0, 0, 0);
    }
    private static List<String> ids(CloudlyShadowState.Update<?> update) {
        return update.stages().stream().map(CloudlyShadowState.Stage::programId).toList();
    }
}
