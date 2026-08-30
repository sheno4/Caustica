package dev.comfyfluffy.caustica.renderer.presentation;

import org.lwjgl.vulkan.VkDevice;

import java.util.List;

/** Immutable swapchain resources borrowed from the presentation host. */
public record PresentationSwapchain(VkDevice device, long swapchain, int format, int width, int height,
                                    List<Image> images) {
    public PresentationSwapchain {
        images = List.copyOf(images);
    }

    public record Image(long image, long presentSemaphore) { }
}
