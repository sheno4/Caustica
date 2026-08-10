package dev.comfyfluffy.caustica.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.NVDeviceDiagnosticsConfig;
import org.lwjgl.vulkan.VkPhysicalDeviceDiagnosticsConfigFeaturesNV;
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
    private static boolean vendorBinaryRequested;
    private static boolean nvDiagnosticsRequested;

    private MinecraftVulkanDiagnostics() {
    }

    public static void addExtensions(Collection<String> extensions, VulkanPhysicalDevice device) {
        VulkanDiagnostics.logStartup(device.vkPhysicalDevice(), startupInfo(device));
        VulkanDiagnostics.FaultSupport support = device.hasDeviceExtension(
                EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME)
                ? VulkanDiagnostics.queryDeviceFaultSupport(device.vkPhysicalDevice())
                : new VulkanDiagnostics.FaultSupport(false, false);
        boolean heavy = CausticaConfig.Rt.Diagnostics.HEAVY_CRASH_DIAGNOSTICS.value();
        faultRequested = support.fault();
        vendorBinaryRequested = faultRequested && support.vendorBinary() && heavy;
        nvDiagnosticsRequested = heavy
                && device.hasDeviceExtension(NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME)
                && VulkanDiagnostics.supportsNvDiagnostics(device.vkPhysicalDevice());
        VulkanDiagnostics.configureDeviceFault(faultRequested, vendorBinaryRequested, nvDiagnosticsRequested);
        if (faultRequested) {
            addOnce(extensions, EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
            CausticaMod.LOGGER.info("Vulkan device-fault diagnostics requested (vendorBinary={})",
                    vendorBinaryRequested);
        } else {
            CausticaMod.LOGGER.warn("Vulkan device-fault diagnostics unavailable on [{}]", device.deviceName());
        }
        if (nvDiagnosticsRequested) {
            addOnce(extensions, NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME);
            CausticaMod.LOGGER.info("NVIDIA device diagnostics config requested");
        }
    }

    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args) {
        if (!faultRequested && !nvDiagnosticsRequested) return;
        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        if (faultRequested) {
            VulkanPNextStruct faultStruct = new VulkanPNextStruct(
                    EXTDeviceFault.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT,
                    VkPhysicalDeviceFaultFeaturesEXT.SIZEOF);
            features.add(new VulkanFeature(faultStruct, "deviceFault", VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT));
            if (vendorBinaryRequested) {
                features.add(new VulkanFeature(faultStruct, "deviceFaultVendorBinary",
                        VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULTVENDORBINARY));
            }
        }
        if (nvDiagnosticsRequested) {
            VulkanPNextStruct configStruct = new VulkanPNextStruct(
                    NVDeviceDiagnosticsConfig.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DIAGNOSTICS_CONFIG_FEATURES_NV,
                    VkPhysicalDeviceDiagnosticsConfigFeaturesNV.SIZEOF);
            features.add(new VulkanFeature(configStruct, "diagnosticsConfig",
                    VkPhysicalDeviceDiagnosticsConfigFeaturesNV.DIAGNOSTICSCONFIG));
        }
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
