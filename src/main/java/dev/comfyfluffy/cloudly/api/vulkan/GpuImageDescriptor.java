/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

/** Borrowed local descriptor whose resource is an image. */
public interface GpuImageDescriptor extends GpuResourceDescriptor {
    /** The image interpretation encoded in this immutable descriptor entry. */
    GpuImageDescriptorKind kind();
}
