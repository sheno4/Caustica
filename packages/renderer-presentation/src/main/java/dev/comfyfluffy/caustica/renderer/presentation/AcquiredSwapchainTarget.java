package dev.comfyfluffy.caustica.renderer.presentation;

/** One host-acquired swapchain image and the binary semaphores governing its presentation. */
public record AcquiredSwapchainTarget(long image, int width, int height,
                                      long acquireSemaphore, long presentSemaphore) { }
