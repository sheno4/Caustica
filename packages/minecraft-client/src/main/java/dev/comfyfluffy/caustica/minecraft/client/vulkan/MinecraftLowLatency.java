package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanDeviceCapabilities;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;

/** Device-scoped implementation of the host's NV low-latency hooks. */
final class MinecraftLowLatency implements VulkanLowLatency {
    private static final long SLEEP_WAIT_TIMEOUT_NS = 200_000_000L;

    private final VulkanDeviceCapabilities capabilities;
    private long timelineSemaphore;
    private long simulationId;
    private long presentId;
    private long swapchain;
    private boolean lastBoost;
    private int lastMinimumIntervalUs;
    private boolean failed;

    MinecraftLowLatency(VulkanDeviceCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public boolean active() {
        return capabilities.lowLatency() && CausticaClientComposition.current().runtime().active()
                && CausticaConfig.Rt.Reflex.ENABLED.value() && !failed;
    }

    @Override public long appliedSwapchain() { return swapchain; }
    @Override public long currentSimulationId() { return simulationId; }
    @Override public long advancePresentId() { return ++presentId; }

    @Override
    public void applySleepMode(VkDevice device, long targetSwapchain) {
        if (!active() || targetSwapchain == 0L) return;
        boolean boost = CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value();
        int minimumIntervalUs = CausticaConfig.Rt.Reflex.MINIMUM_INTERVAL_US.value();
        if (targetSwapchain == swapchain && boost == lastBoost && minimumIntervalUs == lastMinimumIntervalUs) return;
        try {
            ensureSemaphore(device);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkLatencySleepModeInfoNV info = VkLatencySleepModeInfoNV.calloc(stack).sType$Default()
                        .lowLatencyMode(true).lowLatencyBoost(boost).minimumIntervalUs(minimumIntervalUs);
                check(NVLowLatency2.vkSetLatencySleepModeNV(device, targetSwapchain, info),
                        "vkSetLatencySleepModeNV");
            }
            swapchain = targetSwapchain;
            lastBoost = boost;
            lastMinimumIntervalUs = minimumIntervalUs;
            CausticaMod.LOGGER.info("Reflex: sleep mode applied (boost={}, minIntervalUs={})", boost,
                    minimumIntervalUs);
        } catch (Throwable failure) {
            failed = true;
            CausticaMod.LOGGER.error("Reflex: applySleepMode failed; Reflex disabled for this device", failure);
        }
    }

    @Override
    public void sleep(VkDevice device, long targetSwapchain) {
        if (!active() || targetSwapchain == 0L || targetSwapchain != swapchain || timelineSemaphore == 0L) return;
        simulationId++;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkLatencySleepInfoNV sleep = VkLatencySleepInfoNV.calloc(stack).sType$Default()
                    .signalSemaphore(timelineSemaphore).value(simulationId);
            check(NVLowLatency2.vkLatencySleepNV(device, targetSwapchain, sleep), "vkLatencySleepNV");
            VkSemaphoreWaitInfo wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(timelineSemaphore)).pValues(stack.longs(simulationId));
            int result = VK12.vkWaitSemaphores(device, wait, SLEEP_WAIT_TIMEOUT_NS);
            if (result != VK10.VK_SUCCESS) {
                CausticaMod.LOGGER.warn("Reflex: vkWaitSemaphores({}) returned {}; pacing skipped", simulationId,
                        result);
            }
        } catch (Throwable failure) {
            failed = true;
            CausticaMod.LOGGER.error("Reflex: sleep failed; Reflex disabled for this device", failure);
        }
    }

    @Override
    public void marker(VkDevice device, long targetSwapchain, int marker, long id) {
        if (!active() || targetSwapchain == 0L || targetSwapchain != swapchain) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSetLatencyMarkerInfoNV info = VkSetLatencyMarkerInfoNV.calloc(stack).sType$Default()
                    .presentID(id).marker(marker);
            NVLowLatency2.vkSetLatencyMarkerNV(device, targetSwapchain, info);
        } catch (Throwable failure) {
            CausticaMod.LOGGER.warn("Reflex: latency marker {} failed", marker, failure);
        }
    }

    private void ensureSemaphore(VkDevice device) {
        if (timelineSemaphore != 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            VkSemaphoreCreateInfo create = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            var out = stack.mallocLong(1);
            check(VK10.vkCreateSemaphore(device, create, null, out), "vkCreateSemaphore(reflex timeline)");
            timelineSemaphore = out.get(0);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }

    @Override
    public void destroy(VkDevice device) {
        if (timelineSemaphore != 0L) VK10.vkDestroySemaphore(device, timelineSemaphore, null);
        timelineSemaphore = 0L;
        simulationId = 0L;
        presentId = 0L;
        swapchain = 0L;
        failed = false;
    }
}
