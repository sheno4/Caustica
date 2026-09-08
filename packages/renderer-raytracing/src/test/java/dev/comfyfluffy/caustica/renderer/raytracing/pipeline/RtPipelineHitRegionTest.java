package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPipelineHitRegionTest {
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
