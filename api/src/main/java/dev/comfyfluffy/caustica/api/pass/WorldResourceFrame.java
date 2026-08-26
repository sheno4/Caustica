package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import dev.comfyfluffy.caustica.api.option.OptionValues;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Passed to {@link WorldResourcePass#record} once per rendered frame. The command buffer is mid-recording:
 * the engine owns submission and the barriers into and out of the pre-trace stage, but barriers
 * <em>between this pass's own dispatches</em> are the pass's job — {@link #memoryBarrier}, or a raw
 * {@code vkCmdPipelineBarrier} on {@link #commandBuffer()} for anything more specific.
 */
public interface WorldResourceFrame {
    /** The command buffer currently being recorded. Record compute work directly onto it. */
    VkCommandBuffer commandBuffer();

    /** GPU services for pass-local resources. */
    GpuDevice device();

    /** Completion reservation covering every GPU resource this frame's recorded work references. */
    GpuFrameUse gpuUse();

    /**
     * This feature's option values, frozen for the whole frame. A value changed mid-frame becomes visible
     * next frame, so every stage of one frame sees one consistent set.
     */
    OptionValues options();

    /**
     * Republish a world resource whose handle can change between frames — a borrowed view replaced by a
     * resource reload, say, with no epoch callback to publish the new one from. Same contract as
     * {@link WorldResourceSetup#publishWorldTexture}; the engine picks the new handle up after the
     * pre-trace stage and before the world shader is traced.
     */
    void publishWorldTexture(String name, long imageView, int imageLayout, long sampler);

    /**
     * A full pipeline barrier (all commands, all memory) between two of this pass's own dispatches — the
     * same broad, safe idiom the engine uses between its own stages.
     */
    void memoryBarrier();
}
