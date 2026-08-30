package dev.comfyfluffy.caustica.nvidia.nrd;

/** Vulkan device handles and queue-family metadata borrowed by one NRD device lifetime. */
public record NrdDevice(long instance, long physicalDevice, long device, int graphicsQueueFamily, int queuedFrames) {
    public NrdDevice {
        if (instance == 0L || physicalDevice == 0L || device == 0L) {
            throw new IllegalArgumentException("Vulkan device handles must be non-null");
        }
        if (graphicsQueueFamily < 0) throw new IllegalArgumentException("graphicsQueueFamily must be non-negative");
        if (queuedFrames < 1 || queuedFrames > 255) {
            throw new IllegalArgumentException("queuedFrames must be in [1, 255]");
        }
    }
}
