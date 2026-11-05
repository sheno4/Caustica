/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

/** CPU-side descriptor declaration resolved into an ordinary Vulkan descriptor set. */
public record ResourceDescriptor(int type, ImageDescriptor image, long address, long byteSize, int format) {
    public static ResourceDescriptor image(int type, ImageDescriptor image) {
        return new ResourceDescriptor(type, image, 0, 0, 0);
    }
    public static ResourceDescriptor buffer(int type, long address, long byteSize) {
        return new ResourceDescriptor(type, null, address, byteSize, 0);
    }
    public static ResourceDescriptor texels(int type, long address, long byteSize, int format) {
        return new ResourceDescriptor(type, null, address, byteSize, format);
    }
}
