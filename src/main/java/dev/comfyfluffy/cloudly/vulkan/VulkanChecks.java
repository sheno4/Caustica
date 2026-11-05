/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.vulkan;

import org.lwjgl.vulkan.VK10;

public final class VulkanChecks {
    private VulkanChecks() { }

    public static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
