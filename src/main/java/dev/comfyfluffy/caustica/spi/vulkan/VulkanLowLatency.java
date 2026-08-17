package dev.comfyfluffy.caustica.spi.vulkan;

import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VkDevice;

/** Host-owned low-latency pacing and marker service for the active Vulkan device. */
public interface VulkanLowLatency {
    int SIMULATION_START = NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_START_NV;
    int SIMULATION_END = NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_END_NV;
    int RENDER_SUBMIT_START = NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_START_NV;
    int RENDER_SUBMIT_END = NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_END_NV;
    int PRESENT_START = NVLowLatency2.VK_LATENCY_MARKER_PRESENT_START_NV;
    int PRESENT_END = NVLowLatency2.VK_LATENCY_MARKER_PRESENT_END_NV;

    boolean active();

    long appliedSwapchain();

    long currentSimulationId();

    long advancePresentId();

    void applySleepMode(VkDevice device, long swapchain);

    void sleep(VkDevice device, long swapchain);

    void marker(VkDevice device, long swapchain, int marker, long id);

    void destroy(VkDevice device);
}
