package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.resource.FrameResources;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
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
    private Indices indices;

    ShowcaseDescriptorTable(GpuDevice gpu, ResourceFactory factory) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.factory = factory;
    }

    Indices replace(VkResourceDescriptorInfoEXT texture, VkSamplerCreateInfo sampler) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(sampler, "sampler");
        var nextResources = gpu.descriptorHeap().allocateResources(1);
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> nextSamplers = null;
        Indices nextIndices;
        ResourceOwner nextOwner;
        try {
            nextSamplers = gpu.descriptorHeap().allocateSamplers(1);
            var writer = gpu.descriptorHeap().writer();
            writer.writeResource(nextResources, 0, texture);
            writer.writeSampler(nextSamplers, 0, sampler);
            nextIndices = new Indices(nextResources.firstIndex(), nextSamplers.firstIndex());
            var lifetime = new ResourceLifetime(nextResources::destroy, nextSamplers::destroy);
            nextOwner = factory.create(lifetime::close);
        } catch (RuntimeException | Error failure) {
            var allocatedSamplers = nextSamplers;
            try {
                new ResourceLifetime(nextResources::destroy, () -> {
                    if (allocatedSamplers != null) allocatedSamplers.destroy();
                }).close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        var previousOwner = owner;
        indices = nextIndices;
        owner = nextOwner;
        if (previousOwner != null) previousOwner.close();
        return indices;
    }

    Indices capture(FrameResources use) {
        use.retain(owner);
        return indices;
    }

    @Override
    public void close() {
        var closing = owner;
        owner = null;
        indices = null;
        if (closing != null) closing.close();
    }
}
