/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

/** Shader-visible interpretation used when encoding an image into the resource heap. */
public enum GpuImageDescriptorKind {
    SAMPLED,
    STORAGE
}
