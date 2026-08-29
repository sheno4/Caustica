package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ShowcaseSceneApiTest {
    @Test
    void meshPublicationAcceptsTypedAddressRanges() throws Exception {
        assertNotNull(ShowcaseScene.class.getDeclaredMethod("publishMesh",
                VulkanDeviceAddressRange.class, VulkanDeviceAddressRange.class,
                VulkanDeviceAddressRange.class, Runnable.class));
    }
}
