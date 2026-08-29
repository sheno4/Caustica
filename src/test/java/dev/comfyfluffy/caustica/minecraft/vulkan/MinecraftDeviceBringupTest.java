package dev.comfyfluffy.caustica.minecraft.vulkan;

import dev.comfyfluffy.caustica.rt.GpuRasterCapabilities;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanProfileSupport;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanProfileValidation;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanRequiredProfile;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftDeviceBringupTest {
    @Test
    void requestsVulkan14AndRejectsAnOlderLoaderWithStructuredDiagnostic() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MinecraftDeviceBringup.requestInstanceApiVersion(
                        VulkanRequiredProfile.makeApiVersion(0, 1, 3, 0)));

        assertTrue(failure.getMessage().startsWith("Unsupported Vulkan profile:"));
        assertTrue(failure.getMessage().contains("LOADER_API_VERSION"));
        assertEquals(VulkanRequiredProfile.VULKAN_1_4,
                MinecraftDeviceBringup.requestInstanceApiVersion(VulkanRequiredProfile.VULKAN_1_4));
    }

    @Test
    void mapsEveryRequiredFeatureToAQueriedDeviceFeature() {
        assertEquals(VulkanRequiredProfile.CAUSTICA_1_4.features(),
                MinecraftDeviceBringup.mappedProfileFeatures());
    }

    @Test
    void aggregatesDeviceVersionExtensionAndFeatureFailures() {
        VulkanRequiredProfile required = VulkanRequiredProfile.CAUSTICA_1_4;
        Set<String> extensions = new HashSet<>(required.deviceExtensions());
        extensions.remove("VK_EXT_descriptor_heap");
        Set<VulkanFeature> features = new HashSet<>(required.features());
        features.remove(VulkanFeature.SHADER_OBJECT);
        VulkanProfileSupport support = new VulkanProfileSupport(
                required.apiVersion(), required.apiVersion(),
                VulkanRequiredProfile.makeApiVersion(0, 1, 3, 0), extensions, features);

        VulkanProfileValidation validation = MinecraftDeviceBringup.validateProfile(support);

        assertFalse(validation.supported());
        assertEquals(3, validation.issues().size());
        assertTrue(validation.diagnostic().contains("PHYSICAL_DEVICE_API_VERSION"));
        assertTrue(validation.diagnostic().contains("VK_EXT_descriptor_heap"));
        assertTrue(validation.diagnostic().contains("shaderObject"));
    }

    @Test
    void capsOverlaySamplesAtFourAndFallsBackInOrder() {
        assertEquals(VK10.VK_SAMPLE_COUNT_4_BIT, MinecraftDeviceBringup.preferredOverlaySampleCount(
                VK10.VK_SAMPLE_COUNT_8_BIT | VK10.VK_SAMPLE_COUNT_4_BIT | VK10.VK_SAMPLE_COUNT_2_BIT));
        assertEquals(VK10.VK_SAMPLE_COUNT_2_BIT, MinecraftDeviceBringup.preferredOverlaySampleCount(
                VK10.VK_SAMPLE_COUNT_8_BIT | VK10.VK_SAMPLE_COUNT_2_BIT));
        assertEquals(VK10.VK_SAMPLE_COUNT_1_BIT,
                MinecraftDeviceBringup.preferredOverlaySampleCount(VK10.VK_SAMPLE_COUNT_8_BIT));
    }

    @Test
    void rejectsInvalidRasterLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuRasterCapabilities(true, Float.NaN, VK10.VK_SAMPLE_COUNT_4_BIT));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuRasterCapabilities(true, 4.0f, 3));
    }
}
