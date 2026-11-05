/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.resource;

/** Borrowed access to the resources owned by one frame's execution. */
@FunctionalInterface
public interface FrameResources {
    /**
     * Retain a resource before recording commands that access it. The frame acquires its own claim and
     * releases it after completion or abandonment; the caller's ownership is unchanged.
     * This capability is valid only during the pass invocation that supplied it.
     */
    void retain(ResourceOwner resource);
}
