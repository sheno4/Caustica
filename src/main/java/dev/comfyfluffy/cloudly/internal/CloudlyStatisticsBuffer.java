/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.internal;

import dev.comfyfluffy.cloudly.api.resource.ResourceFactory;
import dev.comfyfluffy.cloudly.api.resource.ResourceOwner;
import dev.comfyfluffy.cloudly.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.cloudly.api.vulkan.GpuDevice;
import dev.comfyfluffy.cloudly.vulkan.ResourceLifetime;
import dev.comfyfluffy.cloudly.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import dev.comfyfluffy.cloudly.api.vulkan.ResourceDescriptor;


import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/** Original positive-density min/max reduction writes float bit patterns through a uint texel buffer. */
public record CloudlyStatisticsBuffer(int channels, VmaMappedBuffer buffer,
                                      GpuDescriptorIndex.Resource descriptor, ResourceOwner owner) implements AutoCloseable {
    public static CloudlyStatisticsBuffer create(GpuDevice gpu, ResourceFactory resources, int channels) {
        if (channels < 1 || channels > 4) throw new IllegalArgumentException("Original statistics require one to four channels");
        var buffer = VmaMappedBuffer.createAsync(gpu, channels * 8L, VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT,
                "Cloudly original density statistics");
        dev.comfyfluffy.cloudly.api.vulkan.GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try {
            var bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            for (int channel = 0; channel < channels; channel++) {
                bytes.putInt(channel * 8, -1);
                bytes.putInt(channel * 8 + 4, 0);
            }
            buffer.flush(0, channels * 8L);
            descriptors = gpu.descriptorHeap().allocateResources(1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gpu.descriptorHeap().writer().writeResource(descriptors, 0,
                        ResourceDescriptor.texels(VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
                                buffer.deviceRange().address().value(), channels * 8L, VK_FORMAT_R32_UINT));
            }
            var range = descriptors;
            var owner = resources.create(() -> new ResourceLifetime(range::destroy, buffer::close).close());
            return new CloudlyStatisticsBuffer(channels, buffer, descriptors.firstIndex(), owner);
        } catch (RuntimeException | Error failure) {
            var range = descriptors;
            ResourceLifetime.closeAfterFailure(failure, () -> { if (range != null) range.destroy(); }, buffer::close);
            throw failure;
        }
    }

    /** Call only after the generating job has completed; each pair remains in source min/max channel order. */
    public float[] readCompleted() {
        buffer.invalidate(0, channels * 8L);
        var bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[channels * 2];
        for (int index = 0; index < result.length; index++) result[index] = Float.intBitsToFloat(bytes.getInt(index * 4));
        return result;
    }

    public ResourceOwner retain() { return owner.retain(); }
    @Override public void close() { owner.close(); }
}
