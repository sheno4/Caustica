package dev.comfyfluffy.caustica.vulkan;

import org.lwjgl.vulkan.VK10;

final class VulkanChecks {
    private VulkanChecks() { }

    static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
