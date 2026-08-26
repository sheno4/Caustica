package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * What every pass gets while recording one frame, whatever stage it records in.
 *
 * <p>The command buffer is mid-recording: the engine owns submission and the barriers into and out of each
 * stage, but barriers <em>between one pass's own dispatches</em> are that pass's job.
 *
 * <p>Subtypes add what the stage itself produces — {@link PostEffectFrame} the scene image and the chain,
 * {@link dev.comfyfluffy.caustica.api.ui.UiFrame} the layer and the camera. A stage that produces nothing
 * for a pass to read takes this type unchanged, which is the honest statement that at that point in the
 * frame there is nothing to offer.
 */
public interface PassFrame {
    /** The command buffer currently being recorded. Record work directly onto it. */
    VkCommandBuffer commandBuffer();

    /**
     * GPU services for pass-local resources. The same device {@link PassSetup#device()} gave, repeated
     * because a pass that only ever records has no reason to have kept it.
     */
    GpuDevice device();

    /**
     * Completion reservation covering every GPU resource this frame's recorded work references.
     *
     * <p>{@link GpuFrameUse#retire} is the tighter form of {@link GpuDevice#retireAfterUse}: this frame's
     * work is not submitted yet, so only this reservation covers it. Nothing here needs
     * {@link GpuFrameUse#awaitCompletion} — replacing a resource and retiring the old one is the documented
     * pattern, and it never blocks.
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
     * A full pipeline barrier (all commands, all memory) between two of this pass's own dispatches — the
     * same broad, safe idiom the engine uses between its own stages.
     */
    void memoryBarrier();
}
