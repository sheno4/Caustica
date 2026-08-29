package dev.comfyfluffy.caustica.api.vulkan;

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
                () -> new GpuDescriptorHeapProperties(0, 8, 1024, 256, 128, 64, 256, 64));
    }

    @Test
    void resourceStrideIsUnifiedAcrossImageAndBufferDescriptors() {
        GpuDescriptorHeapProperties properties = new GpuDescriptorHeapProperties(
                32, 8, 1024, 256, 128, 64, 256, 64);

        assertEquals(32, properties.resourceDescriptorStride());
        assertEquals(8, properties.samplerDescriptorStride());
        assertEquals(1024, properties.resourceDescriptorCapacity());
        assertEquals(256, properties.samplerDescriptorCapacity());
        assertEquals(128, properties.maximumResourceAllocation());
        assertEquals(64, properties.maximumSamplerAllocation());
        assertEquals(256, properties.resourceHeapAlignment());
        assertEquals(64, properties.samplerHeapAlignment());
    }

    @Test
    void descriptorPropertiesValidateAllocationLimitsAndHeapAlignment() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(32, 8, 64, 32, 65, 8, 256, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(32, 8, 64, 32, 16, 8, 96, 64));
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
                UiFrame.class.getMethod("entrySceneTlasDescriptor").getReturnType());
        assertEquals(GpuDescriptorIndex.Resource.class,
                GpuResourceDescriptor.class.getMethod("index").getReturnType());
        assertEquals(GpuDescriptorIndex.class,
                GpuDescriptorRange.class.getMethod("firstIndex").getReturnType());
    }

    @Test
    void descriptorIndexRetainsItsHeapKind() {
        assertEquals(17, new GpuDescriptorIndex.Resource(17).value());
        assertEquals(9, new GpuDescriptorIndex.Sampler(9).value());
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorIndex.Sampler(-1));
    }

    @Test
    void retainedDeviceAddressRangeChecksSlicesAndUnsignedOverflow() {
        VulkanDeviceAddressRange range = new VulkanDeviceAddressRange(
                new VulkanDeviceAddress(0x1000L), 64L);

        assertEquals(0x1010L, range.slice(16L, 32L).address().value());
        assertThrows(IllegalArgumentException.class, () -> range.slice(48L, 32L));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanDeviceAddress(-8L).addBytes(16L));
    }

    @Test
    void lwjglTypesOnlyDispatchableVulkanHandles() throws ReflectiveOperationException {
        assertEquals(VkCommandBuffer.class, PassFrame.class.getMethod("commandBuffer").getReturnType());
        assertEquals(long.class, GpuImage.class.getMethod("image").getReturnType());
        assertEquals(long.class, GpuImage.class.getMethod("view").getReturnType());
    }
}
