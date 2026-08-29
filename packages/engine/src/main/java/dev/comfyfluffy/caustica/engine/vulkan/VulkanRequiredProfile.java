package dev.comfyfluffy.caustica.engine.vulkan;

import java.util.Set;

/** Immutable Vulkan requirements shared by device discovery and logical-device creation. */
public record VulkanRequiredProfile(int apiVersion, Set<String> deviceExtensions, Set<VulkanFeature> features) {
    public static final int VULKAN_1_4 = makeApiVersion(0, 1, 4, 0);

    public static final VulkanRequiredProfile CAUSTICA_1_4 = new VulkanRequiredProfile(
            VULKAN_1_4,
            Set.of(
                    "VK_KHR_unified_image_layouts",
                    "VK_EXT_descriptor_heap",
                    "VK_EXT_shader_object",
                    "VK_KHR_shader_untyped_pointers",
                    "VK_KHR_acceleration_structure",
                    "VK_KHR_deferred_host_operations",
                    "VK_KHR_ray_tracing_pipeline",
                    "VK_KHR_ray_query",
                    "VK_KHR_ray_tracing_position_fetch"
            ),
            Set.of(VulkanFeature.values())
    );

    public VulkanRequiredProfile {
        if (apiVersion == 0) throw new IllegalArgumentException("apiVersion must not be zero");
        deviceExtensions = Set.copyOf(deviceExtensions);
        features = Set.copyOf(features);
    }

    public static int makeApiVersion(int variant, int major, int minor, int patch) {
        if ((variant & ~0x7) != 0 || (major & ~0x7f) != 0
                || (minor & ~0x3ff) != 0 || (patch & ~0xfff) != 0) {
            throw new IllegalArgumentException("Vulkan API version component is out of range");
        }
        return variant << 29 | major << 22 | minor << 12 | patch;
    }

    public static String formatApiVersion(int version) {
        int variant = version >>> 29;
        int major = version >>> 22 & 0x7f;
        int minor = version >>> 12 & 0x3ff;
        int patch = version & 0xfff;
        String base = major + "." + minor + "." + patch;
        return variant == 0 ? base : variant + ":" + base;
    }
}
