/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;

/** A dedicated Vulkan image whose backing allocation can be imported into OpenGL. */
public final class SharedImage implements AutoCloseable {
    private final CloudlyDevice owner;
    private final int width;
    private final int height;
    private final int format;
    private final long allocationSize;
    private long image;
    private long memory;
    private long view;
    private long memoryHandle;

    private SharedImage(CloudlyDevice owner, int width, int height, int format,
                        long image, long memory, long view, long memoryHandle, long allocationSize) {
        this.owner = owner;
        this.width = width;
        this.height = height;
        this.format = format;
        this.image = image;
        this.memory = memory;
        this.view = view;
        this.memoryHandle = memoryHandle;
        this.allocationSize = allocationSize;
    }

    CloudlyDevice owner() { return owner; }
    public int width() { return width; }
    public int height() { return height; }
    public int format() { return format; }
    public long image() { return image; }
    public long view() { return view; }
    public long allocationSize() { return allocationSize; }
    public long memoryOffset() { return 0; }
    public long memoryHandle() { return memoryHandle; }
    public long win32Handle() { return memoryHandle; }

    static SharedImage create(CloudlyDevice owner, VkPhysicalDevice physical,
                              int width, int height, int format, int usage) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Shared image size must be positive");
        long image = 0;
        long memory = 0;
        long view = 0;
        long handle = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            validateFormat(physical, width, height, format, usage, stack);
            var external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var create = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external.address())
                    .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .extent(extent -> extent.set(width, height, 1));
            var pointer = stack.mallocLong(1);
            CloudlyDevice.check(vkCreateImage(owner.vk(), create, null, pointer), "create shared image");
            image = pointer.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(owner.vk(), image, requirements);
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            var export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(dedicated.address()).handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(export.address())
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(deviceMemoryType(physical, requirements.memoryTypeBits(), stack));
            CloudlyDevice.check(vkAllocateMemory(owner.vk(), allocation, null, pointer), "allocate shared image memory");
            memory = pointer.get(0);
            CloudlyDevice.check(vkBindImageMemory(owner.vk(), image, memory, 0), "bind shared image memory");
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            CloudlyDevice.check(vkCreateImageView(owner.vk(), viewInfo, null, pointer), "create shared image view");
            view = pointer.get(0);
            var get = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default().memory(memory)
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            PointerBuffer handlePointer = stack.mallocPointer(1);
            CloudlyDevice.check(vkGetMemoryWin32HandleKHR(owner.vk(), get, handlePointer), "export shared image memory");
            handle = handlePointer.get(0);
            return new SharedImage(owner, width, height, format, image, memory, view, handle, requirements.size());
        } catch (Throwable failure) {
            if (handle != 0) Win32Handles.close(handle);
            if (view != 0) vkDestroyImageView(owner.vk(), view, null);
            if (image != 0) vkDestroyImage(owner.vk(), image, null);
            if (memory != 0) vkFreeMemory(owner.vk(), memory, null);
            throw failure;
        }
    }

    private static void validateFormat(VkPhysicalDevice physical, int width, int height,
                                       int format, int usage, MemoryStack stack) {
        var external = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default()
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        var query = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().pNext(external.address())
                .format(format).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage);
        var externalProperties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        var properties = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(externalProperties.address());
        CloudlyDevice.check(vkGetPhysicalDeviceImageFormatProperties2(physical, query, properties),
                "query exportable image format " + format);
        if ((externalProperties.externalMemoryProperties().externalMemoryFeatures()
                & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0
                || (externalProperties.externalMemoryProperties().compatibleHandleTypes()
                & VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT) == 0) {
            throw new IllegalStateException("Shared image format cannot export opaque Win32 memory: " + format);
        }
        if (width > properties.imageFormatProperties().maxExtent().width()
                || height > properties.imageFormatProperties().maxExtent().height()) {
            throw new IllegalArgumentException("Shared image exceeds GPU format extent limits");
        }
    }

    private static int deviceMemoryType(VkPhysicalDevice physical, int allowed, MemoryStack stack) {
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physical, properties);
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((allowed & (1 << index)) != 0
                    && (properties.memoryTypes(index).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                return index;
            }
        }
        throw new IllegalStateException("No device-local memory type for shared image");
    }

    /** Requires all GPU use and importing GL objects to have been retired by the caller. */
    @Override public void close() {
        if (image == 0) return;
        try {
            Win32Handles.close(memoryHandle);
        } finally {
            vkDestroyImageView(owner.vk(), view, null);
            vkDestroyImage(owner.vk(), image, null);
            vkFreeMemory(owner.vk(), memory, null);
            image = memory = view = memoryHandle = 0;
            owner.forget(this);
        }
    }
}
