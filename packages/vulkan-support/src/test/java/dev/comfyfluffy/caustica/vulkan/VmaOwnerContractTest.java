package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkImageCreateInfo;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class VmaOwnerContractTest {
    @Test
    void hostBufferDoesNotExposeOrRequireDeviceAddresses() throws Exception {
        assertNotNull(VmaMappedHostBuffer.class.getDeclaredMethod(
                "create", GpuDevice.class, long.class, int.class, String.class));
        assertFalse(Arrays.stream(VmaMappedHostBuffer.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("deviceAddress")
                        || method.getName().equals("deviceRange")));
    }

    @Test
    void imageOwnerAcceptsTheCompleteVulkanImageDescription() throws Exception {
        assertNotNull(VmaImageAllocation.class.getDeclaredMethod(
                "create", GpuDevice.class, VkImageCreateInfo.class, String.class));
        assertNotNull(VmaImageAllocation.class.getDeclaredMethod("image"));
        assertEquals(VmaImageAllocation.class,
                VmaImage2D.class.getDeclaredField("imageAllocation").getType());
    }
}
