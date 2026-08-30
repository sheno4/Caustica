package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Bridges host device wrappers and checkpoint formatting into raw Vulkan diagnostics. */
public final class MinecraftVulkanDiagnostics {
    private static boolean faultRequested;

    private MinecraftVulkanDiagnostics() {
    }

    public static void addExtensions(Collection<String> extensions, VulkanPhysicalDevice device) {
        VulkanDiagnostics.logStartup(device.vkPhysicalDevice(), startupInfo(device));
        faultRequested = device.hasDeviceExtension(
                EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME)
                ? VulkanDiagnostics.queryDeviceFaultSupport(device.vkPhysicalDevice())
                : false;
        VulkanDiagnostics.configureDeviceFault(faultRequested);
        if (faultRequested) {
            addOnce(extensions, EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
            CausticaMod.LOGGER.info("Vulkan device-fault diagnostics requested");
        } else {
            CausticaMod.LOGGER.warn("Vulkan device-fault diagnostics unavailable on [{}]", device.deviceName());
        }
    }

    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args) {
        if (!faultRequested) return;
        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        VulkanPNextStruct faultStruct = new VulkanPNextStruct(
                EXTDeviceFault.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT,
                VkPhysicalDeviceFaultFeaturesEXT.SIZEOF);
        features.add(new VulkanFeature(faultStruct, "deviceFault", VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT));
        args.set(2, features);
    }

    public static void reportDeviceLost(VulkanDevice device, String operation) {
        if (VulkanDiagnostics.deviceLossAlreadyReported()) return;
        try {
            String checkpoints = VulkanUtils.formatCheckpoints(
                    device.checkpointExtension().retrieveCheckpoints(true));
            CausticaMod.LOGGER.error("Vulkan queue checkpoints:\n{}",
                    checkpoints.isBlank() ? "<none>" : checkpoints);
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Failed to retrieve Vulkan queue checkpoints", t);
        }
        VulkanDiagnostics.reportDeviceLost(device.vkDevice(), operation);
    }

    private static VulkanDiagnostics.StartupInfo startupInfo(VulkanPhysicalDevice device) {
        var driver = device.vkPhysicalDeviceDriverProperties();
        var conformance = driver.conformanceVersion();
        var vk11 = device.vkPhysicalDeviceVulkan11Properties();
        return new VulkanDiagnostics.StartupInfo(device.deviceName(), device.vendorName(), String.valueOf(device.deviceType()),
                driver.driverNameString(), driver.driverInfoString(), driver.driverID(),
                hex(vk11.deviceUUID()), hex(vk11.driverUUID()),
                String.format(Locale.ROOT, "%d.%d.%d.%d", conformance.major(), conformance.minor(),
                        conformance.subminor(), conformance.patch()),
                "graphics=" + device.graphicsQueueFamilyAndIndex()
                        + ", compute=" + device.computeQueueFamilyAndIndex()
                        + ", transfer=" + device.transferQueueFamilyAndIndex());
    }

    private static String hex(ByteBuffer bytes) {
        StringBuilder result = new StringBuilder(bytes.remaining() * 2);
        for (int i = bytes.position(); i < bytes.limit(); i++) {
            result.append(String.format(Locale.ROOT, "%02x", bytes.get(i) & 0xff));
        }
        return result.toString();
    }

    private static void addOnce(Collection<String> extensions, String extension) {
        if (!extensions.contains(extension)) extensions.add(extension);
    }
}
