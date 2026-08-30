package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.Objects;

/** Extension-owned sampler descriptor encoded directly from its Vulkan creation parameters. */
public final class VulkanSampler implements AutoCloseable {
    private final GpuDescriptorRange<GpuDescriptorIndex.Sampler> descriptor;
    private final ResourceLifetime lifetime;

    private VulkanSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> descriptor) {
        this.descriptor = descriptor;
        this.lifetime = new ResourceLifetime(descriptor::destroy);
    }

    public static VulkanSampler linearClamp(GpuDevice gpu) {
        return clamp(gpu, VK10.VK_FILTER_LINEAR);
    }

    public static VulkanSampler nearestClamp(GpuDevice gpu) {
        return clamp(gpu, VK10.VK_FILTER_NEAREST);
    }

    private static VulkanSampler clamp(GpuDevice gpu, int filter) {
        Objects.requireNonNull(gpu, "gpu");
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> descriptor = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f);
            descriptor = gpu.descriptorHeap().allocateSamplers(1);
            gpu.descriptorHeap().writer().writeSampler(descriptor, 0, info);
            return new VulkanSampler(descriptor);
        } catch (RuntimeException | Error failure) {
            if (descriptor != null) descriptor.destroy();
            throw failure;
        }
    }

    public GpuDescriptorIndex.Sampler index() { return descriptor.firstIndex(); }

    /** Release the heap range after its GPU uses have drained. */
    @Override
    public void close() {
        lifetime.close();
    }
}
