package dev.comfyfluffy.caustica.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.gpu.GpuRasterCapabilities;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanDeviceCapabilities;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanQueueReservation;
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
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
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

/** Negotiates renderer Vulkan features and transfers the immutable result to the matching device backend. */
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
    private static final VulkanPNextStruct PRESENT_ID_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR,
            VkPhysicalDevicePresentIdFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct OMM_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);

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
    private static final VulkanFeature PRESENT_ID = new VulkanFeature(PRESENT_ID_STRUCT,
            "presentId", VkPhysicalDevicePresentIdFeaturesKHR.PRESENTID);
    private static final VulkanFeature OMM = new VulkanFeature(OMM_STRUCT,
            "opacityMicromap", VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature WIDE_LINES = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
            "wideLines", VkPhysicalDeviceFeatures.WIDELINES);
    private static final List<VulkanFeature> REQUIRED_FEATURES = List.of(BUFFER_ADDRESS, RUNTIME_ARRAY,
            NON_UNIFORM_IMAGE, PARTIALLY_BOUND, UPDATE_AFTER_BIND, SHADER_INT64, ACCELERATION_STRUCTURE,
            RAY_PIPELINE, POSITION_FETCH, RAY_QUERY);

    private static boolean loggedUnavailable;
    private static final Map<Long, Negotiation> NEGOTIATIONS = new HashMap<>();

    public record NegotiatedDevice(VulkanDeviceCapabilities capabilities, VulkanQueueReservation computeQueue) {
    }

    private static final class Negotiation {
        VulkanDeviceCapabilities capabilities = VulkanDeviceCapabilities.unavailable();
        VulkanQueueReservation computeQueue;
        boolean hdrMetadata;
    }

    private record Support(List<String> missing, boolean ser, boolean omm, boolean presentId, boolean wideLines) {
        boolean rayTracing() {
            return missing.isEmpty();
        }
    }

    private MinecraftDeviceBringup() {
    }

    private static synchronized Negotiation negotiation(long physicalDevice) {
        return NEGOTIATIONS.computeIfAbsent(physicalDevice, ignored -> new Negotiation());
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
        negotiation(device.vkPhysicalDevice().address()).hdrMetadata = supported;
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
        Negotiation negotiation = negotiation(device.vkPhysicalDevice().address());
        negotiation.capabilities = VulkanDeviceCapabilities.unavailable();
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
        int samples = preferredOverlaySampleCount(
                device.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts());
        negotiation.capabilities = new VulkanDeviceCapabilities(true, support.ser(), support.omm(), 0,
                lowLatency, presentIds, negotiation.hdrMetadata,
                new GpuRasterCapabilities(support.wideLines(), maxLineWidth, samples));
        CausticaMod.LOGGER.info("Ray tracing enabled on [{}]: SER={}, OMM={}, Reflex={}, presentId={}, overlaySamples={}",
                device.deviceName(), support.ser(), support.omm(), lowLatency, presentIds, samples);
    }

    public static void reserveComputeQueue(VkDeviceCreateInfo createInfo, VulkanPhysicalDevice device,
                                           MemoryStack stack) {
        Negotiation negotiation = negotiation(device.vkPhysicalDevice().address());
        negotiation.computeQueue = reserveComputeQueue(createInfo, device.vkPhysicalDevice(), stack,
                negotiation.capabilities.rayTracing());
    }

    /** Validates negotiated entry points and captures device limits before the backend is published. */
    public static synchronized void probe(VkDevice device) {
        Negotiation negotiation = negotiation(device.getPhysicalDevice().address());
        VulkanDeviceCapabilities old = negotiation.capabilities;
        if (!old.rayTracing()) return;
        VKCapabilitiesDevice entryPoints = device.getCapabilities();
        boolean rayTracing = entryPoints.vkCreateRayTracingPipelinesKHR != 0L
                && entryPoints.vkCmdBuildAccelerationStructuresKHR != 0L
                && entryPoints.vkCmdTraceRaysKHR != 0L;
        boolean omm = rayTracing && old.opacityMicromaps() && entryPoints.vkCreateMicromapEXT != 0L
                && entryPoints.vkCmdBuildMicromapsEXT != 0L
                && entryPoints.vkGetMicromapBuildSizesEXT != 0L
                && entryPoints.vkDestroyMicromapEXT != 0L;
        boolean lowLatency = old.lowLatency() && entryPoints.vkSetLatencySleepModeNV != 0L
                && entryPoints.vkLatencySleepNV != 0L && entryPoints.vkSetLatencyMarkerNV != 0L
                && entryPoints.vkGetLatencyTimingsNV != 0L;
        int maxOmmSubdivision = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                    VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default().pNext(asProps.address());
            if (omm) {
                VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps =
                        VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
                asProps.pNext(ommProps.address());
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                        .pNext(rtProps);
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
                maxOmmSubdivision = ommProps.maxOpacity4StateSubdivisionLevel();
            } else {
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                        .pNext(rtProps);
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
            }
            CausticaMod.LOGGER.info("RT device limits: groupHandle={}, groupAlignment={}, recursionDepth={}, maxGeometry={}",
                    rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(),
                    rtProps.maxRayRecursionDepth(), asProps.maxGeometryCount());
        }
        negotiation.capabilities = new VulkanDeviceCapabilities(rayTracing,
                rayTracing && old.shaderExecutionReordering(), omm,
                maxOmmSubdivision, lowLatency, lowLatency && old.presentIds(), old.hdrMetadata(), old.raster());
        if (!rayTracing) CausticaMod.LOGGER.error("RT entry points are incomplete after device creation");
        if (old.opacityMicromaps() && !omm) CausticaMod.LOGGER.error("Opacity micromap entry points are incomplete");
        if (old.lowLatency() && !lowLatency) CausticaMod.LOGGER.error("Low-latency entry points are incomplete");
    }

    /** Removes the negotiation result once the matching live backend has captured it. */
    public static synchronized NegotiatedDevice consume(VkDevice device) {
        Negotiation negotiation = NEGOTIATIONS.remove(device.getPhysicalDevice().address());
        if (negotiation == null || negotiation.computeQueue == null) return null;
        return new NegotiatedDevice(negotiation.capabilities, negotiation.computeQueue);
    }

    static int preferredOverlaySampleCount(int supportedCounts) {
        if ((supportedCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) return VK10.VK_SAMPLE_COUNT_4_BIT;
        if ((supportedCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) return VK10.VK_SAMPLE_COUNT_2_BIT;
        return VK10.VK_SAMPLE_COUNT_1_BIT;
    }

    private static VulkanQueueReservation reserveComputeQueue(VkDeviceCreateInfo createInfo,
            org.lwjgl.vulkan.VkPhysicalDevice physicalDevice, MemoryStack stack, boolean rayTracing) {
        if (!rayTracing) return null;
        VkDeviceQueueCreateInfo.Buffer requestedQueues = createInfo.pQueueCreateInfos();
        if (requestedQueues == null) return null;
        var count = stack.callocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
        int[] requestedCounts = new int[families.capacity()];
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            requestedCounts[request.queueFamilyIndex()] = request.queueCount();
        }
        int family = selectComputeQueueFamily(families, requestedCounts);
        if (family < 0) {
            CausticaMod.LOGGER.warn("Renderer RT disabled: no spare compute-capable Vulkan queue is available");
            return null;
        }
        int queueIndex = requestedCounts[family];
        VkDeviceQueueCreateInfo matching = null;
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            if (requestedQueues.get(i).queueFamilyIndex() == family) matching = requestedQueues.get(i);
        }
        if (matching != null) {
            var oldPriorities = matching.pQueuePriorities();
            var priorities = stack.callocFloat(queueIndex + 1);
            if (oldPriorities != null) for (int i = 0; i < queueIndex; i++) priorities.put(i, oldPriorities.get(i));
            matching.pQueuePriorities(priorities);
        } else {
            VkDeviceQueueCreateInfo.Buffer expanded = VkDeviceQueueCreateInfo.calloc(requestedQueues.capacity() + 1, stack);
            for (int i = 0; i < requestedQueues.capacity(); i++) {
                VkDeviceQueueCreateInfo source = requestedQueues.get(i);
                expanded.get(i).sType(source.sType()).pNext(source.pNext()).flags(source.flags())
                        .queueFamilyIndex(source.queueFamilyIndex()).pQueuePriorities(source.pQueuePriorities());
            }
            expanded.get(requestedQueues.capacity()).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(stack.callocFloat(1));
            createInfo.pQueueCreateInfos(expanded);
        }
        CausticaMod.LOGGER.info("Reserved renderer compute queue family={} index={}", family, queueIndex);
        return new VulkanQueueReservation(family, queueIndex);
    }

    static int selectComputeQueueFamily(VkQueueFamilyProperties.Buffer families, int[] requestedCounts) {
        int selected = -1;
        int score = Integer.MAX_VALUE;
        for (int family = 0; family < families.capacity(); family++) {
            int flags = families.get(family).queueFlags();
            if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) == 0 || requestedCounts[family] >= families.get(family).queueCount()) continue;
            int candidate = ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0 ? 1_000 : 0)
                    + Integer.bitCount(flags) * 10 + requestedCounts[family];
            if (candidate < score) { selected = family; score = candidate; }
        }
        return selected;
    }

    private static Support querySupport(VulkanPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            REQUIRED_FEATURES.forEach(feature -> feature.struct().findOrCreateStructInPNextChain(available, stack));
            boolean querySer = device.hasDeviceExtension(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            boolean queryOmm = device.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
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
