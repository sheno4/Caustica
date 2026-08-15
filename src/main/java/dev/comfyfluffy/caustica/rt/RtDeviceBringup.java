package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/** Raw Vulkan capability state consumed by the renderer after host device negotiation. */
public final class RtDeviceBringup {
    public record Capabilities(
            boolean rayTracing,
            boolean shaderExecutionReordering,
            boolean opacityMicromaps,
            boolean lowLatency,
            boolean presentIds,
            boolean wideLines,
            float maxLineWidth,
            int overlayMsaaSamples) {
        public Capabilities {
            if (!Float.isFinite(maxLineWidth) || maxLineWidth < 1.0f) {
                throw new IllegalArgumentException("maxLineWidth must be finite and at least 1.0");
            }
            if (!validSampleCount(overlayMsaaSamples)) {
                throw new IllegalArgumentException("overlayMsaaSamples must be a single Vulkan sample-count bit");
            }
        }

        public static Capabilities unavailable() {
            return new Capabilities(false, false, false, false, false, false,
                    1.0f, VK10.VK_SAMPLE_COUNT_1_BIT);
        }
    }

    private static volatile Capabilities capabilities = Capabilities.unavailable();
    private static volatile int maxOpacity4StateSubdivisionLevel;
    private static volatile int computeQueueFamilyIndex = -1;
    private static volatile int computeQueueIndex = -1;

    private RtDeviceBringup() {
    }

    public static void publish(Capabilities negotiated) {
        capabilities = negotiated;
        maxOpacity4StateSubdivisionLevel = 0;
    }

    public static boolean rtRequested() {
        return capabilities.rayTracing();
    }

    public static boolean serExtEnabled() {
        return capabilities.shaderExecutionReordering();
    }

    public static boolean ommEnabled() {
        return capabilities.opacityMicromaps();
    }

    public static boolean reflexEnabled() {
        return capabilities.lowLatency();
    }

    public static boolean presentIdEnabled() {
        return capabilities.presentIds();
    }

    public static boolean wideLinesEnabled() {
        return capabilities.wideLines();
    }

    public static float maxLineWidth() {
        return capabilities.maxLineWidth();
    }

    public static int overlayMsaaSamples() {
        return capabilities.overlayMsaaSamples();
    }

    public static int maxOpacity4StateSubdivisionLevel() {
        return maxOpacity4StateSubdivisionLevel;
    }

    public static boolean computeQueueReserved() {
        return computeQueueFamilyIndex >= 0 && computeQueueIndex >= 0;
    }

    public static int computeQueueFamilyIndex() {
        if (!computeQueueReserved()) {
            throw new IllegalStateException("Renderer compute queue was not reserved");
        }
        return computeQueueFamilyIndex;
    }

    public static int computeQueueIndex() {
        if (!computeQueueReserved()) {
            throw new IllegalStateException("Renderer compute queue was not reserved");
        }
        return computeQueueIndex;
    }

    /** Reserves one additional physical compute queue without depending on host queue-wrapper types. */
    public static void reserveComputeQueue(VkDeviceCreateInfo createInfo, VkPhysicalDevice physicalDevice,
                                           MemoryStack stack) {
        computeQueueFamilyIndex = -1;
        computeQueueIndex = -1;
        if (!rtRequested()) {
            return;
        }
        VkDeviceQueueCreateInfo.Buffer requestedQueues = createInfo.pQueueCreateInfos();
        if (requestedQueues == null) {
            CausticaMod.LOGGER.warn("Renderer RT disabled: Vulkan device creation has no queue requests");
            return;
        }

        var count = stack.callocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
        int[] requestedCounts = new int[families.capacity()];
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            int family = request.queueFamilyIndex();
            if (family >= 0 && family < requestedCounts.length) {
                requestedCounts[family] = request.queueCount();
            }
        }

        int selectedFamily = selectComputeQueueFamily(families, requestedCounts);
        if (selectedFamily < 0) {
            CausticaMod.LOGGER.warn("Renderer RT disabled: no spare compute-capable Vulkan queue is available");
            return;
        }
        int queueIndex = requestedCounts[selectedFamily];
        VkDeviceQueueCreateInfo matchingRequest = null;
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            if (request.queueFamilyIndex() == selectedFamily) {
                matchingRequest = request;
                break;
            }
        }
        if (matchingRequest != null) {
            var oldPriorities = matchingRequest.pQueuePriorities();
            var priorities = stack.callocFloat(queueIndex + 1);
            if (oldPriorities != null) {
                for (int i = 0; i < queueIndex; i++) {
                    priorities.put(i, oldPriorities.get(i));
                }
            }
            matchingRequest.pQueuePriorities(priorities);
        } else {
            VkDeviceQueueCreateInfo.Buffer expanded = VkDeviceQueueCreateInfo.calloc(requestedQueues.capacity() + 1, stack);
            for (int i = 0; i < requestedQueues.capacity(); i++) {
                VkDeviceQueueCreateInfo source = requestedQueues.get(i);
                expanded.get(i).sType(source.sType()).pNext(source.pNext()).flags(source.flags())
                        .queueFamilyIndex(source.queueFamilyIndex()).pQueuePriorities(source.pQueuePriorities());
            }
            expanded.get(requestedQueues.capacity()).sType$Default().queueFamilyIndex(selectedFamily)
                    .pQueuePriorities(stack.callocFloat(1));
            createInfo.pQueueCreateInfos(expanded);
        }
        computeQueueFamilyIndex = selectedFamily;
        computeQueueIndex = queueIndex;
        CausticaMod.LOGGER.info("Reserved renderer compute queue family={} index={}", selectedFamily, queueIndex);
    }

    static int selectComputeQueueFamily(VkQueueFamilyProperties.Buffer families, int[] requestedCounts) {
        int selectedFamily = -1;
        int selectedScore = Integer.MAX_VALUE;
        for (int family = 0; family < families.capacity(); family++) {
            int flags = families.get(family).queueFlags();
            if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) == 0
                    || requestedCounts[family] >= families.get(family).queueCount()) {
                continue;
            }
            int score = ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0 ? 1_000 : 0)
                    + Integer.bitCount(flags) * 10 + requestedCounts[family];
            if (score < selectedScore) {
                selectedFamily = family;
                selectedScore = score;
            }
        }
        return selectedFamily;
    }

    public static int preferredOverlaySampleCount(int supportedCounts) {
        if ((supportedCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) return VK10.VK_SAMPLE_COUNT_4_BIT;
        if ((supportedCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) return VK10.VK_SAMPLE_COUNT_2_BIT;
        return VK10.VK_SAMPLE_COUNT_1_BIT;
    }

    private static boolean validSampleCount(int sampleCount) {
        return sampleCount == VK10.VK_SAMPLE_COUNT_1_BIT || sampleCount == VK10.VK_SAMPLE_COUNT_2_BIT
                || sampleCount == VK10.VK_SAMPLE_COUNT_4_BIT || sampleCount == VK10.VK_SAMPLE_COUNT_8_BIT
                || sampleCount == VK10.VK_SAMPLE_COUNT_16_BIT || sampleCount == VK10.VK_SAMPLE_COUNT_32_BIT
                || sampleCount == VK10.VK_SAMPLE_COUNT_64_BIT;
    }

    /** Verifies negotiated entry points and captures raw ray-tracing device limits. */
    public static void probe(VkDevice device) {
        if (!rtRequested()) {
            maxOpacity4StateSubdivisionLevel = 0;
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                CausticaMod.LOGGER.error("RT entry points missing (pipeline={}, build={}, trace={})",
                        rtPipeline, asBuild, traceRays);
                return;
            }
            if (ommEnabled() && (caps.vkCreateMicromapEXT == 0L || caps.vkCmdBuildMicromapsEXT == 0L
                    || caps.vkGetMicromapBuildSizesEXT == 0L || caps.vkDestroyMicromapEXT == 0L)) {
                Capabilities old = capabilities;
                capabilities = new Capabilities(old.rayTracing(), old.shaderExecutionReordering(), false,
                        old.lowLatency(), old.presentIds(), old.wideLines(), old.maxLineWidth(),
                        old.overlayMsaaSamples());
                CausticaMod.LOGGER.error("Opacity micromap extension was enabled but its entry points are incomplete");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default().pNext(asProps.address());
                VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps = null;
                if (ommEnabled()) {
                    ommProps = VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
                    asProps.pNext(ommProps.address());
                }
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                        .pNext(rtProps.address());
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
                CausticaMod.LOGGER.info("RT device limits: groupHandle={}, groupAlignment={}, recursionDepth={}, maxGeometry={}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(),
                        rtProps.maxRayRecursionDepth(), asProps.maxGeometryCount());
                maxOpacity4StateSubdivisionLevel = ommProps == null ? 0 : ommProps.maxOpacity4StateSubdivisionLevel();
            }
            if (reflexEnabled() && (caps.vkSetLatencySleepModeNV == 0L || caps.vkLatencySleepNV == 0L
                    || caps.vkSetLatencyMarkerNV == 0L || caps.vkGetLatencyTimingsNV == 0L)) {
                Capabilities old = capabilities;
                capabilities = new Capabilities(old.rayTracing(), old.shaderExecutionReordering(),
                        old.opacityMicromaps(), false, false, old.wideLines(), old.maxLineWidth(),
                        old.overlayMsaaSamples());
                CausticaMod.LOGGER.error("Low-latency extension was enabled but its entry points are incomplete");
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("RT device probe failed", t);
        }
    }
}
