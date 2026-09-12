package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan.HitGroup;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPipelineHitRegionTest {
    @Test
    void shadowGroupsUseTheirDedicatedPayloadStages() {
        for (HitGroup group : HitGroup.values()) {
            boolean shadow = switch (group) {
                case SHADOW_OPAQUE, SHADOW_CUTOUT, SHADOW_TRANSMISSIVE, SHADOW_BLOCKER -> true;
                case RADIANCE_OPAQUE, RADIANCE_CUTOUT -> false;
            };
            assertEquals(group == HitGroup.SHADOW_BLOCKER ? VK_SHADER_UNUSED_KHR : shadow ? 13 : 7,
                    RtPipeline.closestHitStage(group, 7, 13));
            int anyHit = RtPipeline.anyHitStage(group, 8, 14, 19);
            if (group == HitGroup.SHADOW_BLOCKER) {
                assertEquals(19, anyHit);
            } else if (group == HitGroup.SHADOW_OPAQUE || group == HitGroup.RADIANCE_OPAQUE) {
                assertEquals(VK_SHADER_UNUSED_KHR, anyHit);
            } else {
                assertEquals(shadow ? 14 : 8, anyHit);
            }
        }
    }

    @Test
    void emptySceneUsesAnEmptyHitRegionAndPopulatedSceneRetainsItsRecords() {
        try (var stack = MemoryStack.stackPush()) {
            var empty = RtPipeline.hitRegion(stack, null);
            assertEquals(0L, empty.deviceAddress());
            assertEquals(0L, empty.stride());
            assertEquals(0L, empty.size());

            var populated = RtPipeline.hitRegion(stack, new RtPipeline.HitTable(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(4096L), 192L), 64L));
            assertEquals(4096L, populated.deviceAddress());
            assertEquals(64L, populated.stride());
            assertEquals(192L, populated.size());
        }
    }
}
