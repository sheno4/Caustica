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
        handles.position(3);

        ByteBuffer packed = RtPipeline.packRetainedHitRecords(handles, 4, 8, List.of(
                RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT,
                RtRetainedGeometryPlan.HitGroup.SHADOW_CUTOUT,
                RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                RtRetainedGeometryPlan.HitGroup.SHADOW_OPAQUE,
                RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                RtRetainedGeometryPlan.HitGroup.SHADOW_TRANSMISSIVE));

        assertEquals(101, packed.getInt(0));
        assertEquals(104, packed.getInt(8));
        assertEquals(100, packed.getInt(16));
        assertEquals(103, packed.getInt(24));
        assertEquals(100, packed.getInt(32));
        assertEquals(105, packed.getInt(40));
        for (int record = 0; record < 6; record++) assertEquals(0, packed.getInt(record * 8 + 4));
        assertEquals(3, handles.position());
        assertEquals(0, packed.position());
        assertEquals(48, packed.remaining());
    }
}
