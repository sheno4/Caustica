package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.OptionValues;
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
     * The display-res scene image as it stands at this point in the post chain: scene-linear ACEScg, not
     * yet exposed, look-transformed or tone mapped. For the first pass that takes a target it is the
     * reconstructed HDR colour after DLSS-RR (or the no-RR blit); after that it is whatever the previous
     * participating pass wrote. Engine-produced and resolved fresh every frame — never cache the returned
     * {@link GpuImage} across frames, since it can be recreated on resize.
     */
    GpuImage sceneColor();

    /**
     * Join the post chain: the image this pass writes its version of the scene into. Taking a target is
     * what makes a pass part of the chain — the engine then barriers after it and hands what it wrote to
     * the next pass's {@link #sceneColor()}, and the display map reads whatever the last one produced.
     * A pass that never calls this (a sky bake, a light upload) leaves the chain untouched and costs it
     * nothing.
     *
     * <p>Distinct from {@link #sceneColor()}, always — write every pixel, including the ones the effect
     * does not change, since the target holds the previous frame's chain contents rather than a copy of
     * the source. That is what lets an effect gather from neighbouring pixels safely, which reading and
     * writing one image could not.
     *
     * <p>Two targets rotate, so calling this twice in one {@code record()} returns the same image; a pass
     * that needs its own intermediates allocates them itself.
     */
    GpuImage sceneColorTarget();

    /** The current frame's scalar exposure value. Engine-produced; resolved fresh every frame, see above. */
    GpuImage exposureImage();

    /** This feature's option snapshot for the current frame. */
    OptionValues options();

    /**
     * Re-publish a world resource whose handle the host application can change between frames — a
     * Minecraft atlas is the case this exists for: its {@code GpuTextureView} is replaced by a resource
     * reload, and the pass wrapping it has no create/resize call to publish the new one from. Same
     * contract as {@link PassSetup#publishWorldResource(String, GpuImage, long)} otherwise; the engine
     * picks the new handle up at its next descriptor rebind, not mid-frame.
     */
    void publishWorldResource(String name, long imageView, long sampler);

    /**
     * A full pipeline barrier (all commands, all memory read/write) between two of this pass's own
     * dispatches — the same broad, safe idiom the engine itself uses between frame stages.
     */
    void memoryBarrier();
}
