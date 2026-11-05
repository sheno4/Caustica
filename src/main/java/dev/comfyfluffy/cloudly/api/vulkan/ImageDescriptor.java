/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

import org.lwjgl.vulkan.VkImageViewCreateInfo;

/** Synchronously consumed image-view declaration; its view only lives for the current call. */
public record ImageDescriptor(VkImageViewCreateInfo view, int layout) { }
