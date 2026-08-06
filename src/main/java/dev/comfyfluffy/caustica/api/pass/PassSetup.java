package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;

/**
 * Passed to {@link CausticaRenderPass#create} and {@link CausticaRenderPass#resize}. A pass allocates its
 * own {@link GpuImage}/{@link dev.comfyfluffy.caustica.rt.accel.GpuBuffer} resources through
 * {@link #context()} and owns their lifetime — the engine does not track or retire them.
 */
public interface PassSetup {
    /** Raw engine context: device, allocator, image/buffer creation, debug labelling. */
    RtContext context();

    int displayWidth();

    int displayHeight();

    /**
     * Hand the engine a pass-produced image for a fixed slot the engine's own shaders read (e.g. the sky
     * LUTs feeding {@code indirect.rgen}'s {@code celestialLight}). Only valid for
     * {@link EngineImage#passOutput()} slots; at most one pass may publish a given slot. The image must
     * stay valid — and its identity stable across a resize unless republished — for as long as this pass
     * is active.
     */
    default void publish(EngineImage slot, GpuImage image) {
        publish(slot, image, 1);
    }

    /**
     * Same as {@link #publish(EngineImage, GpuImage)}, plus the total level count for a slot backed by a
     * pyramid (e.g. bloom) so engine consumers that need to know the pyramid's depth — not just its base
     * level's image — can read it back via {@code RenderPassManager.levelCount}.
     */
    void publish(EngineImage slot, GpuImage image, int levelCount);
}
