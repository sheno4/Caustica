package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * What every pass gets while recording one frame, whatever stage it records in.
 *
 * <p>The command buffer is mid-recording: the engine owns submission and the barriers into and out of each
 * stage, but barriers <em>between one pass's own dispatches</em> are that pass's job.
 *
 * <p>Subtypes add what the stage itself produces — {@link PostEffectFrame} the scene image and the chain,
 * {@link UiFrame} the layer and the camera. A stage that produces nothing
 * for a pass to read takes this type unchanged, which is the honest statement that at that point in the
 * frame there is nothing to offer.
 *
 * <p>The frame, command buffer, completion reservation, images, descriptor views, and subtype capabilities
 * are borrowed only for the current {@link Pass#record} invocation on that thread. Do not retain any of
 * them or call their methods after the callback returns. Commands recorded during the callback may continue
 * using the borrowed GPU resources; the renderer owns that asynchronous lifetime.
 */
public interface PassFrame {
    /** The command buffer currently being recorded. Record work directly onto it. */
    VkCommandBuffer commandBuffer();

    /**
     * Completion reservation covering every GPU resource this frame's recorded work references.
     *
     * <p>{@link GpuFrameUse#retire} covers this frame's work even though it has not been submitted yet.
     * Register retirement before {@code record} returns. Replacing a resource and retiring the old one is
     * the non-blocking update pattern.
     */
    GpuFrameUse gpuUse();

    /**
     * The renderer's frame counter, increasing by one per rendered frame.
     *
     * <p>Here so that two passes of one extension can tell they are in the same frame — anything that must
     * not change between stages is snapshotted when this value moves. The engine cannot do that snapshotting
     * itself, since it does not know what the state is, but it is the only thing that knows where a frame
     * begins.
     */
    long frameIndex();

    /**
     * The resolution the world was traced at this frame, which is not the display resolution: an upscaler
     * reconstructs to display size afterwards.
     *
     * <p>Per frame rather than announced at a boundary, because there is no boundary that covers it. It
     * moves when the display resizes, and equally when the upscaler's quality mode changes, which no resize
     * callback would have fired for. A pass sizing anything to the trace compares this against what it last
     * built from and rebuilds when it differs.
     */
    int renderWidth();

    int renderHeight();
}
