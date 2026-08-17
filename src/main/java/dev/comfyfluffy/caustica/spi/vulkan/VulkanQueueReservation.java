package dev.comfyfluffy.caustica.spi.vulkan;

/** Queue selected and added to the host's Vulkan device-create request for renderer compute work. */
public record VulkanQueueReservation(int familyIndex, int queueIndex) {
    public VulkanQueueReservation {
        if (familyIndex < 0 || queueIndex < 0) {
            throw new IllegalArgumentException("Vulkan queue indices must not be negative");
        }
    }
}
