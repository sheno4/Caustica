package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPipelineRetainedHitPackingTest {
    @Test
    void duplicatesFixedGroupHandlesInPlacementGeometryRayOrder() {
        ByteBuffer handles = ByteBuffer.allocate(6 * 4);
        for (int group = 0; group < 6; group++) handles.putInt(group * 4, 100 + group);

        ByteBuffer packed = RtPipeline.packRetainedHitRecords(handles, 4, 8, List.of(
                RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT,
                RtRetainedGeometryPlan.HitGroup.SHADOW_CUTOUT,
                RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                RtRetainedGeometryPlan.HitGroup.SHADOW_OPAQUE));

        assertEquals(101, packed.getInt(0));
        assertEquals(104, packed.getInt(8));
        assertEquals(100, packed.getInt(16));
        assertEquals(103, packed.getInt(24));
        assertEquals(0, packed.getInt(4));
    }
}
