package dev.comfyfluffy.caustica.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtHdr;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTHdrMetadata;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR;
import static org.lwjgl.vulkan.NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME;

/** Adapts the host's feature-set based device creation to raw renderer capability state. */
public final class MinecraftDeviceBringup {
    private static final List<String> REQUIRED_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME);

    private static final VulkanPNextStruct AS_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct PIPELINE_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct POSITION_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct QUERY_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct SER_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct OMM_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct PRESENT_ID_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR,
            VkPhysicalDevicePresentIdFeaturesKHR.SIZEOF);

    private static final VulkanFeature BUFFER_ADDRESS = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature RUNTIME_ARRAY = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "runtimeDescriptorArray", VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY);
    private static final VulkanFeature NON_UNIFORM_IMAGE = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "shaderSampledImageArrayNonUniformIndexing",
            VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING);
    private static final VulkanFeature PARTIALLY_BOUND = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "descriptorBindingPartiallyBound", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND);
    private static final VulkanFeature UPDATE_AFTER_BIND = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "descriptorBindingSampledImageUpdateAfterBind",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND);
    private static final VulkanFeature SHADER_INT64 = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature ACCELERATION_STRUCTURE = new VulkanFeature(AS_STRUCT,
            "accelerationStructure", VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_PIPELINE = new VulkanFeature(PIPELINE_STRUCT,
            "rayTracingPipeline", VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);
    private static final VulkanFeature POSITION_FETCH = new VulkanFeature(POSITION_STRUCT,
            "rayTracingPositionFetch", VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH);
    private static final VulkanFeature RAY_QUERY = new VulkanFeature(QUERY_STRUCT,
            "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
    private static final VulkanFeature SER = new VulkanFeature(SER_STRUCT,
            "rayTracingInvocationReorder(EXT)",
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER);
    private static final VulkanFeature OMM = new VulkanFeature(OMM_STRUCT,
            "micromap", VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature PRESENT_ID = new VulkanFeature(PRESENT_ID_STRUCT,
            "presentId", VkPhysicalDevicePresentIdFeaturesKHR.PRESENTID);
    private static final VulkanFeature WIDE_LINES = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
            "wideLines", VkPhysicalDeviceFeatures.WIDELINES);
    private static final List<VulkanFeature> REQUIRED_FEATURES = List.of(BUFFER_ADDRESS, RUNTIME_ARRAY,
            NON_UNIFORM_IMAGE, PARTIALLY_BOUND, UPDATE_AFTER_BIND, SHADER_INT64, ACCELERATION_STRUCTURE,
            RAY_PIPELINE, POSITION_FETCH, RAY_QUERY);

    private static boolean loggedUnavailable;

    private record Support(List<String> missing, boolean ser, boolean omm, boolean presentId, boolean wideLines) {
        boolean rayTracing() {
            return missing.isEmpty();
        }
    }

    private MinecraftDeviceBringup() {
    }

    public static void addExtensions(Collection<String> extensions, VulkanPhysicalDevice device) {
        addHdrExtension(extensions, device);
        String missingExtension = firstMissingExtension(device);
        if (missingExtension != null) return;
        Support support = querySupport(device);
        if (!support.rayTracing()) return;
        REQUIRED_EXTENSIONS.forEach(extension -> addOnce(extensions, extension));
        if (support.ser()) addOnce(extensions, VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
        if (support.omm()) addOnce(extensions, VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        if (CausticaConfig.Rt.Reflex.ENABLED.value()
                && device.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)) {
            addOnce(extensions, VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
            if (support.presentId()) addOnce(extensions, VK_KHR_PRESENT_ID_EXTENSION_NAME);
        }
    }

    private static void addHdrExtension(Collection<String> extensions, VulkanPhysicalDevice device) {
        boolean supported = device.hasDeviceExtension(EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
        RtHdr.publishMetadataExtension(supported);
        if (supported) {
            addOnce(extensions, EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
            CausticaMod.LOGGER.info("HDR: enabling {} for PQ swapchain mastering metadata",
                    EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
        } else {
            CausticaMod.LOGGER.warn("HDR: device [{}] lacks {}; mastering metadata disabled",
                    device.deviceName(), EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
        }
    }

    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args, VulkanPhysicalDevice device) {
        RtDeviceBringup.publish(RtDeviceBringup.Capabilities.unavailable());
        String missingExtension = firstMissingExtension(device);
        if (missingExtension != null) {
            logUnavailable(device, "extension " + missingExtension);
            return;
        }
        Support support = querySupport(device);
        if (!support.rayTracing()) {
            logUnavailable(device, "features " + support.missing());
            return;
        }
        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        features.addAll(REQUIRED_FEATURES);
        if (support.ser()) features.add(SER);
        if (support.omm()) features.add(OMM);
        if (support.wideLines()) features.add(WIDE_LINES);

        boolean lowLatency = CausticaConfig.Rt.Reflex.ENABLED.value()
                && device.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
        boolean presentIds = lowLatency && support.presentId();
        if (presentIds) features.add(PRESENT_ID);
        args.set(2, features);

        float maxLineWidth = support.wideLines()
                ? device.vkPhysicalDeviceProperties().limits().lineWidthRange(1) : 1.0f;
        int samples = RtDeviceBringup.preferredOverlaySampleCount(
                device.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts());
        RtDeviceBringup.publish(new RtDeviceBringup.Capabilities(true, support.ser(), support.omm(),
                lowLatency, presentIds, support.wideLines(), maxLineWidth, samples));
        CausticaMod.LOGGER.info("Ray tracing enabled on [{}]: SER={}, OMM={}, Reflex={}, presentId={}, overlaySamples={}",
                device.deviceName(), support.ser(), support.omm(), lowLatency, presentIds, samples);
    }

    public static void reserveComputeQueue(VkDeviceCreateInfo createInfo, VulkanPhysicalDevice device,
                                           MemoryStack stack) {
        RtDeviceBringup.reserveComputeQueue(createInfo, device.vkPhysicalDevice(), stack);
    }

    private static Support querySupport(VulkanPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            REQUIRED_FEATURES.forEach(feature -> feature.struct().findOrCreateStructInPNextChain(available, stack));
            boolean querySer = device.hasDeviceExtension(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            boolean queryOmm = CausticaConfig.Rt.Omm.ENABLED.value()
                    && device.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
            boolean queryPresent = CausticaConfig.Rt.Reflex.ENABLED.value()
                    && device.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)
                    && device.hasDeviceExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME);
            if (querySer) SER.struct().findOrCreateStructInPNextChain(available, stack);
            if (queryOmm) OMM.struct().findOrCreateStructInPNextChain(available, stack);
            if (queryPresent) PRESENT_ID.struct().findOrCreateStructInPNextChain(available, stack);
            WIDE_LINES.struct().findOrCreateStructInPNextChain(available, stack);
            VK12.vkGetPhysicalDeviceFeatures2(device.vkPhysicalDevice(), available);
            List<String> missing = new ArrayList<>();
            REQUIRED_FEATURES.stream().filter(feature -> !feature.get(available))
                    .map(VulkanFeature::name).forEach(missing::add);
            return new Support(missing, querySer && SER.get(available), queryOmm && OMM.get(available),
                    queryPresent && PRESENT_ID.get(available), WIDE_LINES.get(available));
        }
    }

    private static String firstMissingExtension(VulkanPhysicalDevice device) {
        return REQUIRED_EXTENSIONS.stream().filter(extension -> !device.hasDeviceExtension(extension))
                .findFirst().orElse(null);
    }

    private static void addOnce(Collection<String> extensions, String extension) {
        if (!extensions.contains(extension)) extensions.add(extension);
    }

    private static void logUnavailable(VulkanPhysicalDevice device, String reason) {
        if (!loggedUnavailable) {
            loggedUnavailable = true;
            CausticaMod.LOGGER.warn("Ray tracing unavailable on [{}]: missing {}", device.deviceName(), reason);
        }
    }
}
