package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;

final class RtAccelCowUpdateTest {
    @Test
    void staticBuildAllowsCompactionAndPositionFetchWithoutUpdate() {
        assertEquals(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR
                        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR,
                RtAccel.buildFlags(false));
    }

    @Test
    void refittableBuildAllowsUpdateAndPositionFetchWithoutCompaction() {
        assertEquals(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR
                        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR,
                RtAccel.buildFlags(true));
    }

    @Test
    void updateableBuildAndCowUpdateShareFlagsAndGeometryLayout() {
        RtAccel.BlasLayout layout = new RtAccel.BlasLayout(20, 12, List.of(
                new RtAccel.GeometryRange(0, 6, true),
                new RtAccel.GeometryRange(9, 12, false)));
        RtAccel.BlasOperation build = RtAccel.initialBuildOperation(layout, true);

        RtAccel.BlasOperation update = RtAccel.cowUpdateOperation(build, 0x1234L);

        assertEquals(RtAccel.BlasOperationMode.BUILD, build.mode());
        assertEquals(0L, build.sourceHandle());
        assertEquals(RtAccel.BlasOperationMode.UPDATE, update.mode());
        assertEquals(0x1234L, update.sourceHandle());
        assertSame(layout, update.layout());
        assertEquals(RtAccel.buildFlags(build.updateable()), RtAccel.buildFlags(update.updateable()));
        assertTrue((RtAccel.buildFlags(update.updateable())
                & VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) != 0);
    }

    @Test
    void cowUpdateRejectsANonUpdateableBuild() {
        RtAccel.BlasLayout layout = new RtAccel.BlasLayout(12, 3,
                List.of(new RtAccel.GeometryRange(0, 3, true)));
        RtAccel.BlasOperation build = RtAccel.initialBuildOperation(layout, false);

        assertThrows(IllegalArgumentException.class,
                () -> RtAccel.cowUpdateOperation(build, 0x1234L));
    }

    @Test
    void operationContractRejectsInvalidSourceModes() {
        RtAccel.BlasLayout layout = new RtAccel.BlasLayout(12, 3,
                List.of(new RtAccel.GeometryRange(0, 3, true)));

        assertThrows(IllegalArgumentException.class, () -> new RtAccel.BlasOperation(
                RtAccel.BlasOperationMode.BUILD, 0x1234L, true, layout));
        assertThrows(IllegalArgumentException.class, () -> new RtAccel.BlasOperation(
                RtAccel.BlasOperationMode.UPDATE, 0L, true, layout));
        assertThrows(IllegalArgumentException.class, () -> new RtAccel.BlasOperation(
                RtAccel.BlasOperationMode.UPDATE, 0x1234L, false, layout));
    }

    @Test
    void layoutOwnsAnImmutableGeometryDescription() {
        List<RtAccel.GeometryRange> ranges = new ArrayList<>();
        ranges.add(new RtAccel.GeometryRange(0, 3, true));

        RtAccel.BlasLayout layout = new RtAccel.BlasLayout(12, 3, ranges);
        ranges.clear();

        assertEquals(1, layout.geometryRanges().size());
        assertThrows(UnsupportedOperationException.class,
                () -> layout.geometryRanges().add(new RtAccel.GeometryRange(3, 3, true)));
        assertNotEquals(ranges, layout.geometryRanges());
    }
}
