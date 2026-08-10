package dev.comfyfluffy.caustica.rt.backend;

import org.lwjgl.vulkan.VkCommandBuffer;

/** A deferred host graphics submission. Calls are appended in invocation order to one host submit. */
public interface GraphicsSubmission {
    VkCommandBuffer beginTransientCommandBuffer();

    void waitSemaphore(long semaphore, long value, long stageMask);

    void execute(VkCommandBuffer commandBuffer);

    void signalSemaphore(long semaphore, long value, long stageMask);
}
