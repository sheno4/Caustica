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
     * The reconstructed HDR colour target, after DLSS-RR (or the no-RR blit). Engine-produced and
     * resolved fresh every frame — never cache the returned {@link GpuImage} across frames, since it can
     * be recreated on resize.
     */
    GpuImage reconstructedColor();

    /** The current frame's scalar exposure value. Engine-produced; resolved fresh every frame, see above. */
    GpuImage exposureImage();

    /** This feature's option snapshot for the current frame. */
    PassOptions options();

    /**
     * Re-publish a world resource whose handle the host application can change between frames — a
     * Minecraft atlas is the case this exists for: its {@code GpuTextureView} is replaced by a resource
     * reload, and the pass wrapping it has no create/resize call to publish the new one from. Same
     * contract as {@link PassSetup#publishWorldResource(String, GpuImage, long)} otherwise; the engine
     * picks the new handle up at its next descriptor rebind, not mid-frame.
     */
    void publishWorldResource(String name, long imageView, long sampler);

    /**
     * Publish a scalar another pipeline reads back by name this frame — bloom's composite weight is the
     * case this exists for: the display-mapping pipeline needs one float that is bloom's business to
     * compute, and the pass is the only thing that should know how its own option maps to it. Unlike
     * {@link PassSetup#publishOutput}, this is per-frame, so an option change lands immediately instead
     * of at the next resize.
     */
    void publishScalar(String name, float value);

    /**
     * A full pipeline barrier (all commands, all memory read/write) between two of this pass's own
     * dispatches — the same broad, safe idiom the engine itself uses between frame stages.
     */
    void memoryBarrier();
}
