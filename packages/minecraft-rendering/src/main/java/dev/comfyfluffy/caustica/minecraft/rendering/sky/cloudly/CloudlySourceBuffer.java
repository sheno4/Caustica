package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDeviceAddressRangeEXT;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkTexelBufferDescriptorInfoEXT;
import org.lwjgl.vulkan.VkFormatProperties2;
import org.lwjgl.vulkan.VK11;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Immutable source bytes with a structured or float4 texel descriptor and retained resource dependencies. */
public record CloudlySourceBuffer(int strideBytes, int elementCount, GpuDescriptorIndex.Resource descriptor,
                                  VulkanDeviceAddressRange deviceRange, ResourceOwner owner) implements AutoCloseable {
    /**
     * Captures the caller's remaining bytes before allocating mapped memory shared by graphics and
     * asynchronous compute. Jobs and frames retain the returned owner while its descriptor is reachable.
     * Dependencies are borrowed and receive independent claims until this allocation retires.
     */
    public static CloudlySourceBuffer upload(GpuDevice gpu, ResourceFactory resources, ByteBuffer source,
                                             int strideBytes, List<ResourceOwner> dependencies) {
        return upload(gpu, resources, source, strideBytes, dependencies, false);
    }

    /** Original Buffer<float4> sphere samples use a read-only texel descriptor, with their source bytes intact. */
    public static CloudlySourceBuffer uploadFloat4Texels(GpuDevice gpu, ResourceFactory resources, ByteBuffer source,
                                                          List<ResourceOwner> dependencies) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFormatProperties2(gpu.vk().getPhysicalDevice(), VK_FORMAT_R32G32B32A32_SFLOAT, properties);
            if ((properties.formatProperties().bufferFeatures() & VK_FORMAT_FEATURE_UNIFORM_TEXEL_BUFFER_BIT) == 0) {
                throw new UnsupportedOperationException("Original float4 sphere sample buffer format is unsupported");
            }
        }
        return upload(gpu, resources, source, 16, dependencies, true);
    }

    private static CloudlySourceBuffer upload(GpuDevice gpu, ResourceFactory resources, ByteBuffer source,
                                               int strideBytes, List<ResourceOwner> dependencies, boolean float4Texels) {
        if (strideBytes <= 0 || source.remaining() == 0 || source.remaining() % strideBytes != 0) {
            throw new IllegalArgumentException("Source bytes do not contain complete structured-buffer elements");
        }
        byte[] bytes = new byte[source.remaining()];
        source.duplicate().get(bytes);
        List<ResourceOwner> claims = new ArrayList<>();
        VmaMappedBuffer buffer = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        ResourceOwner owner = null;
        try {
            for (ResourceOwner dependency : dependencies) claims.add(dependency.retain());
            buffer = VmaMappedBuffer.createAsync(gpu, bytes.length,
                    float4Texels ? VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Cloudly immutable source buffer");
            buffer.mapped().put(bytes);
            buffer.flush(0, bytes.length);
            descriptors = gpu.descriptorHeap().allocateResources(1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDeviceAddressRangeEXT address = VkDeviceAddressRangeEXT.calloc(stack)
                        .address$(buffer.deviceRange().address().value()).size(bytes.length);
                var resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default();
                if (float4Texels) {
                    var texels = VkTexelBufferDescriptorInfoEXT.calloc(stack).sType$Default()
                            .format(VK_FORMAT_R32G32B32A32_SFLOAT).addressRange(address);
                    resource.type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).data(data -> data.pTexelBuffer(texels));
                } else resource.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).data(data -> data.pAddressRange(address));
                gpu.descriptorHeap().writer().writeResource(descriptors, 0, resource);
            }
            VmaMappedBuffer allocation = buffer;
            var descriptorRange = descriptors;
            List<ResourceOwner> retainedDependencies = List.copyOf(claims);
            owner = resources.create(() -> new ResourceLifetime(descriptorRange::destroy, allocation::close,
                    () -> new ResourceLifetime(retainedDependencies.stream().<Runnable>map(claim -> claim::close)
                            .toArray(Runnable[]::new)).close()).close());
            return new CloudlySourceBuffer(strideBytes, bytes.length / strideBytes,
                    descriptors.firstIndex(), buffer.deviceRange(), owner);
        } catch (RuntimeException | Error failure) {
            ResourceOwner allocatedOwner = owner;
            VmaMappedBuffer allocation = buffer;
            var descriptorRange = descriptors;
            ResourceLifetime.closeAfterFailure(failure, () -> {
                if (allocatedOwner != null) allocatedOwner.close();
                else new ResourceLifetime(
                        () -> { if (descriptorRange != null) descriptorRange.destroy(); },
                        () -> { if (allocation != null) allocation.close(); },
                        () -> new ResourceLifetime(claims.stream().<Runnable>map(claim -> claim::close)
                                .toArray(Runnable[]::new)).close()).close();
            });
            throw failure;
        }
    }

    /** Holds the descriptor, buffer and dependency graph through a separate GPU job or frame. */
    public ResourceOwner retain() { return owner.retain(); }

    @Override public void close() { owner.close(); }
}
