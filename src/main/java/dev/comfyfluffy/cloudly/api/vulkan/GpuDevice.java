/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

import dev.comfyfluffy.cloudly.vulkan.CloudlyDevice;
import org.lwjgl.vulkan.VkDevice;

/** Services backed by Cloudly's standalone Vulkan device and immutable descriptor registry. */
public final class GpuDevice implements AutoCloseable {
    private final CloudlyDevice device;
    private final GpuDescriptorHeap descriptors;
    public GpuDevice(CloudlyDevice device) {
        this.device = java.util.Objects.requireNonNull(device);
        descriptors = new GpuDescriptorHeap(this);
    }
    public CloudlyDevice device() { return device; }
    public VkDevice vk() { return device.vk(); }
    public long vmaAllocator() { return device.vmaAllocator(); }
    public int[] asyncBufferSharingQueueFamilies() { return new int[] {device.queueFamily()}; }
    public GpuDescriptorHeap descriptorHeap() { return descriptors; }
    @Override public void close() { descriptors.close(); }
}
