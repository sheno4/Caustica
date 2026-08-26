package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.option.OptionValues;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Passed to {@link PostEffectPass#record} once per rendered frame. The command buffer is mid-recording:
 * the engine owns submission and the barriers into and out of the post chain, but barriers <em>between
 * this pass's own dispatches</em> are the pass's job — {@link #memoryBarrier}, or a raw
 * {@code vkCmdPipelineBarrier} on {@link #commandBuffer()} for anything more specific.
 *
 * <p>Both images are display-resolution and carry their own extent, so there is no separate display size
 * to read here and no way for the two to disagree.
 */
public interface PostEffectFrame {
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
     * The scene as it stands at this point in the chain: scene-linear ACEScg, not yet exposed, look
     * transformed, or tone mapped. For the first pass that takes a target it is the reconstructed colour
     * after DLSS-RR (or the no-RR blit); after that it is whatever the previous participating pass wrote.
     *
     * <p>Engine-produced and resolved fresh every frame — never cache the returned {@link GpuImage} across
     * frames, since a resize recreates it.
     */
    GpuImage sceneColor();

    /**
     * Join the chain: the image this pass writes its version of the scene into. Calling this is what
     * enrols the pass — see {@link PostEffectPass}.
     *
     * <p>Always distinct from {@link #sceneColor()}, so write every pixel, including the ones the effect
     * does not change: the target holds the previous frame's chain contents, not a copy of the source.
     * That separation is what lets an effect gather from neighbouring pixels safely, which reading and
     * writing one image could not.
     *
     * <p>Two targets rotate, so calling this twice in one {@code record} returns the same image; a pass
     * needing its own intermediates allocates them itself.
     */
    GpuImage sceneColorTarget();

    /** This frame's scalar exposure. Engine-produced and resolved fresh every frame, as above. */
    GpuImage exposureImage();

    /**
     * A full pipeline barrier (all commands, all memory) between two of this pass's own dispatches — the
     * same broad, safe idiom the engine uses between its own stages.
     */
    void memoryBarrier();
}
