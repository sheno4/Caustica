package dev.comfyfluffy.caustica.minecraft.client.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRGetSurfaceCapabilities2;

final class VulkanInstanceMixinTest {
    private static final String SURFACE_CAPABILITIES_2 =
            KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME;

    @Test
    void enablesTheRequiredModernSurfaceQueryExtension() {
        Set<String> enabled = new HashSet<>();

        assertTrue(VulkanInstanceMixin.enableRequiredSurfaceQuery(Set.of(SURFACE_CAPABILITIES_2), enabled));
        assertTrue(enabled.contains(SURFACE_CAPABILITIES_2));
        assertFalse(VulkanInstanceMixin.enableRequiredSurfaceQuery(Set.of(SURFACE_CAPABILITIES_2), enabled));
    }

    @Test
    void rejectsAnInstanceWithoutTheModernSurfaceQueryExtension() {
        assertThrows(IllegalStateException.class,
                () -> VulkanInstanceMixin.enableRequiredSurfaceQuery(Set.of(), new HashSet<>()));
    }
}
