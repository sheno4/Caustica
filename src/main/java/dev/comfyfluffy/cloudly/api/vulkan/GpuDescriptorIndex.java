/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

/** Typed local handle resolved into an invocation's Vulkan descriptor set before dispatch. */
public sealed interface GpuDescriptorIndex {
    int value();

    /**
     * Handle identifying an image or buffer in the local registry.
     *
     * @param value absolute registry index
     */
    record Resource(int value) implements GpuDescriptorIndex {
        public Resource {
            requireNonNegative(value);
        }
    }

    /**
     * Handle identifying a sampler in the local registry.
     *
     * @param value absolute registry index
     */
    record Sampler(int value) implements GpuDescriptorIndex {
        public Sampler {
            requireNonNegative(value);
        }
    }

    private static void requireNonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("descriptor index must be non-negative");
    }
}
