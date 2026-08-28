package dev.comfyfluffy.caustica.api.gpu;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.UiFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuContractTest {
    @Test
    void descriptorPropertiesRejectNonPowerOfTwoAlignment() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(32, 32, 16, 3, 16, 16, 128));
    }

    @Test
    void descriptorPropertiesRejectNonPowerOfTwoSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(24, 32, 16, 8, 16, 16, 128));
    }

    @Test
    void descriptorPropertiesRejectAlignmentLargerThanDescriptor() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(8, 32, 16, 16, 16, 16, 128));
    }

    @Test
    void resourceStrideIsUnifiedAcrossImageAndBufferDescriptors() {
        GpuDescriptorHeapProperties properties =
                new GpuDescriptorHeapProperties(8, 32, 16, 8, 16, 16, 128);

        assertEquals(32, properties.resourceDescriptorStride());
        assertEquals(8, properties.samplerDescriptorStride());
    }

    @Test
    void engineResourcesExposeBorrowedDescriptorViews() throws ReflectiveOperationException {
        assertEquals(GpuResourceDescriptor.class,
                GpuImage.class.getMethod("descriptor", GpuImageDescriptorKind.class).getReturnType());
        assertEquals(GpuResourceDescriptor.class,
                UiFrame.class.getMethod("sceneTlasDescriptor").getReturnType());
    }

    @Test
    void lwjglTypesOnlyDispatchableVulkanHandles() throws ReflectiveOperationException {
        assertEquals(VkCommandBuffer.class, PassFrame.class.getMethod("commandBuffer").getReturnType());
        assertEquals(long.class, GpuImage.class.getMethod("image").getReturnType());
        assertEquals(long.class, GpuImage.class.getMethod("view").getReturnType());
    }
}
