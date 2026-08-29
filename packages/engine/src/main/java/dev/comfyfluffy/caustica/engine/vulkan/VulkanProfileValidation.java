package dev.comfyfluffy.caustica.engine.vulkan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Structured result of validating one physical-device candidate against a required profile. */
public record VulkanProfileValidation(List<Issue> issues) {
    public enum Category {
        LOADER_API_VERSION,
        INSTANCE_API_VERSION,
        PHYSICAL_DEVICE_API_VERSION,
        DEVICE_EXTENSION,
        DEVICE_FEATURE
    }

    public record Issue(Category category, String requirement, String actual) {
        public Issue {
            if (requirement.isBlank() || actual.isBlank()) {
                throw new IllegalArgumentException("Profile issue fields must not be blank");
            }
        }
    }

    public VulkanProfileValidation {
        issues = List.copyOf(issues);
    }

    public boolean supported() {
        return issues.isEmpty();
    }

    public String diagnostic() {
        if (supported()) return "Vulkan profile supported";
        StringBuilder message = new StringBuilder("Unsupported Vulkan profile:");
        for (Issue issue : issues) {
            message.append(System.lineSeparator()).append(" - ")
                    .append(issue.category()).append(": requires ")
                    .append(issue.requirement()).append(", found ").append(issue.actual());
        }
        return message.toString();
    }

    public static VulkanProfileValidation validate(VulkanRequiredProfile required, VulkanProfileSupport support) {
        ArrayList<Issue> issues = new ArrayList<>();
        requireApiVersion(issues, Category.LOADER_API_VERSION, required.apiVersion(), support.loaderApiVersion());
        requireApiVersion(issues, Category.INSTANCE_API_VERSION, required.apiVersion(), support.requestedInstanceApiVersion());
        requireApiVersion(issues, Category.PHYSICAL_DEVICE_API_VERSION, required.apiVersion(), support.physicalDeviceApiVersion());
        required.deviceExtensions().stream()
                .filter(extension -> !support.deviceExtensions().contains(extension))
                .sorted()
                .map(extension -> new Issue(Category.DEVICE_EXTENSION, extension, "unavailable"))
                .forEach(issues::add);
        required.features().stream()
                .filter(feature -> !support.features().contains(feature))
                .sorted(Comparator.comparing(VulkanFeature::vulkanName))
                .map(feature -> new Issue(Category.DEVICE_FEATURE, feature.vulkanName(), "false"))
                .forEach(issues::add);
        return new VulkanProfileValidation(issues);
    }

    private static void requireApiVersion(List<Issue> issues, Category category, int required, int actual) {
        if (!apiVersionAtLeast(actual, required)) {
            issues.add(new Issue(category, VulkanRequiredProfile.formatApiVersion(required) + " or newer",
                    VulkanRequiredProfile.formatApiVersion(actual)));
        }
    }

    private static boolean apiVersionAtLeast(int actual, int required) {
        if (actual >>> 29 != required >>> 29) return false;
        return Integer.compareUnsigned(actual & 0x1fffffff, required & 0x1fffffff) >= 0;
    }
}
