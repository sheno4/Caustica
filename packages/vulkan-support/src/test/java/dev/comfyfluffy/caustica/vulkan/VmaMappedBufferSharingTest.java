package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VmaMappedBufferSharingTest {
    @Test
    void distinctAsyncFamiliesUseConcurrentSharing() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaMappedBuffer.configureAsyncSharing(info, stack, new int[] { 2, 7 });
            assertEquals(VK10.VK_SHARING_MODE_CONCURRENT, info.sharingMode());
            assertEquals(2, info.queueFamilyIndexCount());
            assertEquals(2, info.pQueueFamilyIndices().get(0));
            assertEquals(7, info.pQueueFamilyIndices().get(1));
        }
    }

    @Test
    void oneAsyncFamilyKeepsExclusiveSharing() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaMappedBuffer.configureAsyncSharing(info, stack, new int[] { 4 });
            assertEquals(VK10.VK_SHARING_MODE_EXCLUSIVE, info.sharingMode());
            assertEquals(0, info.queueFamilyIndexCount());
        }
    }

    @Test
    void asyncSharingRejectsAnEmptyFamilySet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default();
            assertThrows(IllegalArgumentException.class,
                    () -> VmaMappedBuffer.configureAsyncSharing(info, stack, new int[0]));
        }
    }
}
