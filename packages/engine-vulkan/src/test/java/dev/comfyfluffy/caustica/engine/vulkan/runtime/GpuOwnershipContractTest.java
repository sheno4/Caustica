package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GpuOwnershipContractTest {
    @Test
    void rendererOwnershipDoesNotWidenThePublicDeviceOrImageContracts() {
        Set<String> deviceMethods = Arrays.stream(GpuDevice.class.getDeclaredMethods())
                .map(method -> method.getName()).collect(Collectors.toSet());

        assertEquals(Set.of("vk", "vmaAllocator", "descriptorHeap", "retireAfterUse"), deviceMethods);
        assertFalse(Arrays.stream(dev.comfyfluffy.caustica.api.vulkan.GpuImage.class.getMethods())
                .anyMatch(method -> method.getName().equals("destroy")));
        assertTrue(Arrays.stream(GpuImage.class.getMethods())
                .anyMatch(method -> method.getName().equals("destroy")));
        assertTrue(GpuDevice.class.isAssignableFrom(VulkanDeviceContext.class));
    }

    @Test
    void validatesInternalRasterLimits() {
        GpuRasterCapabilities capabilities = new GpuRasterCapabilities(
                true, 8.0f, VK10.VK_SAMPLE_COUNT_4_BIT);

        assertTrue(capabilities.wideLines());
        assertEquals(8.0f, capabilities.maxLineWidth());
        assertEquals(VK10.VK_SAMPLE_COUNT_4_BIT, capabilities.preferredColorSampleCount());
    }

    @Test
    void deviceContextHasFactoryOwnershipWithoutStaticCurrentState() throws Exception {
        assertTrue(Modifier.isStatic(VulkanDeviceContext.class
                .getDeclaredMethod("create", VulkanRendererBackend.class).getModifiers()));
        assertFalse(Arrays.stream(VulkanDeviceContext.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .anyMatch(field -> field.getType() == VulkanDeviceContext.class
                        || field.getType() == VulkanRendererBackend.class));
    }

    @Test
    void rendererBufferKeepsDeviceAddressesTyped() throws Exception {
        assertEquals(VulkanDeviceAddress.class,
                GpuBuffer.class.getMethod("deviceAddress").getReturnType());
        assertEquals(long.class, GpuBuffer.class.getMethod("handle").getReturnType());
        assertEquals(long.class, GpuBuffer.class.getMethod("mapped").getReturnType());
    }
}
