package dev.comfyfluffy.caustica.api.gpu;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.UiFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuContractTest {
    @Test
    void descriptorPropertiesRejectNonPositiveStrides() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(0, 8));
    }

    @Test
    void resourceStrideIsUnifiedAcrossImageAndBufferDescriptors() {
        GpuDescriptorHeapProperties properties = new GpuDescriptorHeapProperties(32, 8);

        assertEquals(32, properties.resourceDescriptorStride());
        assertEquals(8, properties.samplerDescriptorStride());
    }

    @Test
    void engineResourcesExposeBorrowedDescriptorViews() throws ReflectiveOperationException {
        assertEquals(GpuImageDescriptor.class,
                GpuImage.class.getMethod("descriptor", GpuImageDescriptorKind.class).getReturnType());
        assertEquals(GpuImageDescriptorKind.class,
                GpuImageDescriptor.class.getMethod("kind").getReturnType());
        assertEquals(java.util.Set.of(GpuImageDescriptorKind.SAMPLED, GpuImageDescriptorKind.STORAGE),
                java.util.Set.of(GpuImageDescriptorKind.values()));
        assertEquals(GpuAccelerationStructureDescriptor.class,
                UiFrame.class.getMethod("rootSceneTlasDescriptor").getReturnType());
    }

    @Test
    void lwjglTypesOnlyDispatchableVulkanHandles() throws ReflectiveOperationException {
        assertEquals(VkCommandBuffer.class, PassFrame.class.getMethod("commandBuffer").getReturnType());
        assertEquals(long.class, GpuImage.class.getMethod("image").getReturnType());
        assertEquals(long.class, GpuImage.class.getMethod("view").getReturnType());
    }
}
