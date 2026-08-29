package dev.comfyfluffy.caustica.engine.vulkan;

import java.util.Set;

/** Vulkan capabilities observed before logical-device creation. */
public record VulkanProfileSupport(
        int loaderApiVersion,
        int requestedInstanceApiVersion,
        int physicalDeviceApiVersion,
        Set<String> deviceExtensions,
        Set<VulkanFeature> features
) {
    public VulkanProfileSupport {
        if (loaderApiVersion == 0 || requestedInstanceApiVersion == 0 || physicalDeviceApiVersion == 0) {
            throw new IllegalArgumentException("Vulkan API versions must not be zero");
        }
        deviceExtensions = Set.copyOf(deviceExtensions);
        features = Set.copyOf(features);
    }
}
