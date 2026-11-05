/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.pass;

import dev.comfyfluffy.cloudly.api.vulkan.GpuDevice;

/** Services present in the immutable setup for every pass stage in one render session. */
public interface PassSetup {
    /** Session GPU services. */
    GpuDevice gpu();
}
