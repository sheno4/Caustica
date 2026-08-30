package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.Objects;

/**
 * Extension-owned descriptor roots for one texture generation. A replacement is published before the old
 * slots are retired, so already-submitted shader reads keep valid descriptor bytes.
 */
final class ShowcaseDescriptorTable implements AutoCloseable {
    record Indices(GpuDescriptorIndex.Resource texture, GpuDescriptorIndex.Sampler sampler) { }

    private final GpuDevice gpu;
    private GpuDescriptorRange<GpuDescriptorIndex.Resource> resources;
    private GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers;

    ShowcaseDescriptorTable(GpuDevice gpu) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
    }

    Indices replace(VkResourceDescriptorInfoEXT texture, VkSamplerCreateInfo sampler) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(sampler, "sampler");
        var nextResources = gpu.descriptorHeap().allocateResources(1);
        var nextSamplers = gpu.descriptorHeap().allocateSamplers(1);
        var writer = gpu.descriptorHeap().writer();
        writer.writeResource(nextResources, 0, texture);
        writer.writeSampler(nextSamplers, 0, sampler);

        var previousResources = resources;
        var previousSamplers = samplers;
        resources = nextResources;
        samplers = nextSamplers;
        if (previousResources != null) {
            gpu.retireAfterUse(() -> {
                previousResources.destroy();
                previousSamplers.destroy();
            });
        }
        return new Indices(nextResources.firstIndex(), nextSamplers.firstIndex());
    }

    @Override
    public void close() {
        if (resources == null) return;
        var finalResources = resources;
        var finalSamplers = samplers;
        resources = null;
        samplers = null;
        gpu.retireAfterUse(() -> {
            finalResources.destroy();
            finalSamplers.destroy();
        });
    }
}
