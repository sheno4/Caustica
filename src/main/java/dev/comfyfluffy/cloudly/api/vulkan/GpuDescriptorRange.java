/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

/**
 * An owned sequence of typed local handles in the descriptor registry.
 *
 * <p>The range owns its registered native views and samplers. Its shared owner's final-release callback
 * releases the range after each GPU user has relinquished its claim. Each frame retains that owner
 * through {@link dev.comfyfluffy.cloudly.api.pass.PassFrame#retain}. Descriptors are immutable: allocate
 * a replacement range and retain this one until every recorded use retires.
 *
 * @param <I> resource or sampler index type fixed by the allocation method
 */
public interface GpuDescriptorRange<I extends GpuDescriptorIndex> {
    /** Index of the first slot, including its resource or sampler kind. */
    I firstIndex();

    int descriptorCount();

    /**
     * Return this range to the heap allocator after every GPU use has retired. Destruction is exactly once;
     * the range and its indices are invalid afterwards.
     */
    void destroy();
}
