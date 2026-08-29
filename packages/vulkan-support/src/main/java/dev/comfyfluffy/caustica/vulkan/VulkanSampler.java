package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.Objects;

/** Extension-owned sampler and its descriptor-heap range. */
public final class VulkanSampler implements AutoCloseable {
    private final VkDevice device;
    private final long sampler;
    private final GpuDescriptorRange<GpuDescriptorIndex.Sampler> descriptor;
    private final ResourceLifetime lifetime;

    private VulkanSampler(VkDevice device, long sampler,
                          GpuDescriptorRange<GpuDescriptorIndex.Sampler> descriptor) {
        this.device = device;
        this.sampler = sampler;
        this.descriptor = descriptor;
        this.lifetime = new ResourceLifetime(descriptor::destroy,
                () -> VK10.vkDestroySampler(device, sampler, null));
    }

    public static VulkanSampler linearClamp(GpuDevice gpu, String label) {
        return clamp(gpu, label, VK10.VK_FILTER_LINEAR);
    }

    public static VulkanSampler nearestClamp(GpuDevice gpu, String label) {
        return clamp(gpu, label, VK10.VK_FILTER_NEAREST);
    }

    private static VulkanSampler clamp(GpuDevice gpu, String label, int filter) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(label, "label");
        long sampler = 0L;
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> descriptor = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f);
            LongBuffer output = stack.mallocLong(1);
            VulkanChecks.check(VK10.vkCreateSampler(gpu.vk(), info, null, output),
                    "vkCreateSampler(" + label + ")");
            sampler = output.get(0);
            descriptor = gpu.descriptorHeap().allocateSamplers(1, label);
            gpu.descriptorHeap().writer().writeSampler(descriptor, 0, info);
            return new VulkanSampler(gpu.vk(), sampler, descriptor);
        } catch (RuntimeException | Error failure) {
            if (descriptor != null) descriptor.destroy();
            if (sampler != 0L) VK10.vkDestroySampler(gpu.vk(), sampler, null);
            throw failure;
        }
    }

    public GpuDescriptorIndex.Sampler index() { return descriptor.firstIndex(); }

    /** Release the heap range before destroying the sampler it describes. GPU uses must already be drained. */
    @Override
    public void close() {
        lifetime.close();
    }
}
