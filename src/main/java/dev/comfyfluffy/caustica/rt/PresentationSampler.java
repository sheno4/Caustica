package dev.comfyfluffy.caustica.rt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Owns the nearest/clamp sampler shared by presentation compute passes. */
final class PresentationSampler {
    private long handle;

    long ensure(GpuContext context) {
        if (handle != 0L) {
            return handle;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var result = stack.mallocLong(1);
            if (VK10.vkCreateSampler(context.vk(), info, null, result) != VK10.VK_SUCCESS) {
                return 0L;
            }
            handle = result.get(0);
        }
        return handle;
    }

    long handle() {
        return handle;
    }

    void destroy() {
        if (handle == 0L) {
            return;
        }
        GpuContext context = GpuContext.currentOrNull();
        if (context != null) {
            VK10.vkDestroySampler(context.vk(), handle, null);
        }
        handle = 0L;
    }
}
