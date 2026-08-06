package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Passed to {@link CausticaRenderPass#record} once per frame. The command buffer is mid-recording: the
 * engine owns submission and every barrier crossing into or out of this pass's stage, but barriers
 * <em>between this pass's own dispatches</em> are the pass's job — call {@link #memoryBarrier}, or record
 * raw {@code vkCmdPipelineBarrier} calls directly on {@link #commandBuffer()} for anything more specific
 * (e.g. an image layout transition — every engine-created image lives in {@code VK_IMAGE_LAYOUT_GENERAL}
 * permanently, so this is rarely needed).
 */
public interface PassFrame {
    /** The command buffer currently being recorded. Record compute/graphics/RT work directly onto it. */
    VkCommandBuffer commandBuffer();

    long frameIndex();

    int displayWidth();

    int displayHeight();

    /**
     * Resolve an engine image slot's current handle. Resolved fresh every frame — never cache the
     * returned {@link GpuImage} across frames, since engine-owned slots (see
     * {@link EngineImage#passOutput()}) can be recreated on resize.
     */
    GpuImage engineImage(EngineImage slot);

    /** This feature's option snapshot for the current frame. */
    PassOptions options();

    /**
     * A full pipeline barrier (all commands, all memory read/write) between two of this pass's own
     * dispatches — the same broad, safe idiom the engine itself uses between frame stages.
     */
    void memoryBarrier();
}
