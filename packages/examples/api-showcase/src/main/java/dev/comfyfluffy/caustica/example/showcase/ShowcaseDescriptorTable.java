package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.resource.FrameResources;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.Objects;

/**
 * Extension-owned descriptor roots retained independently by every frame that samples them.
 */
final class ShowcaseDescriptorTable implements AutoCloseable {
    record Indices(GpuDescriptorIndex.Resource texture, GpuDescriptorIndex.Sampler sampler) { }

    private final GpuDevice gpu;
    private final ResourceFactory factory;
    private ResourceOwner owner;
    private GpuDescriptorRange<GpuDescriptorIndex.Resource> resources;
    private GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers;

    ShowcaseDescriptorTable(GpuDevice gpu, ResourceFactory factory) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.factory = factory;
    }

    Indices replace(VkResourceDescriptorInfoEXT texture, VkSamplerCreateInfo sampler) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(sampler, "sampler");
        var nextResources = gpu.descriptorHeap().allocateResources(1);
        var nextSamplers = gpu.descriptorHeap().allocateSamplers(1);
        var writer = gpu.descriptorHeap().writer();
        writer.writeResource(nextResources, 0, texture);
        writer.writeSampler(nextSamplers, 0, sampler);

        var nextOwner = factory.create(() -> {
            nextResources.destroy();
            nextSamplers.destroy();
        });
        var previousOwner = owner;
        resources = nextResources;
        samplers = nextSamplers;
        owner = nextOwner;
        if (previousOwner != null) previousOwner.close();
        return new Indices(nextResources.firstIndex(), nextSamplers.firstIndex());
    }

    Indices capture(FrameResources use) {
        use.retain(owner);
        return new Indices(resources.firstIndex(), samplers.firstIndex());
    }

    @Override
    public void close() {
        if (resources == null) return;
        resources = null;
        samplers = null;
        owner.close();
        owner = null;
    }
}
