package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.ResourceId;

/**
 * A unit of GPU work the engine records at a fixed {@link RenderStage}. A pass owns its own Vulkan
 * resources and pipelines outright — created in {@link #create}, resized in {@link #resize}, recorded in
 * {@link #record}, freed in {@link #destroy} — and collects whatever state it needs itself (game state,
 * option values) rather than receiving it pushed in through the frame context.
 *
 * <p>What the engine still owns regardless: command-buffer submission, queue policy, stage ordering, and
 * the two engine-produced inputs a pass may read ({@link PassFrame#reconstructedColor()}/
 * {@link PassFrame#exposureImage()}). Everything else — including the world-pipeline descriptor bindings
 * a pass publishes via {@link PassSetup#publishWorldResource}, discovered from that pass's own Slang
 * rather than declared by the engine — a pass allocates and manages itself; the engine does not track or
 * validate it.
 *
 * <p>A pass that throws from any of these methods is disabled with a logged error; the frame loop
 * continues without it.
 */
public interface CausticaRenderPass {
    ResourceId id();

    RenderStage stage();

    /** Called once, after every pass has been registered and before the first frame. */
    default void create(PassSetup setup) {
    }

    /** Called whenever the display resolution changes, after {@link #create}. */
    default void resize(PassSetup setup, int displayWidth, int displayHeight) {
    }

    /** Called every frame, in stage and registration order, to record this pass's work. */
    void record(PassFrame frame);

    /** Called when the active resource pack is being detached from this render session. */
    default void onResourcePackClosing() {
    }

    /** Called after a replacement resource pack has become the active pack epoch. */
    default void onResourcePackApplied() {
    }

    /** Called whenever this render session enters or leaves a world epoch. */
    default void onWorldChanged() {
    }

    /** Called once, when this pass's feature is deselected or the engine shuts down. */
    default void destroy() {
    }
}
