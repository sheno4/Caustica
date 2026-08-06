package dev.comfyfluffy.caustica.api.pass;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * A unit of GPU work the engine records at a fixed {@link RenderStage}. A pass owns its own Vulkan
 * resources and pipelines outright — created in {@link #create}, resized in {@link #resize}, recorded in
 * {@link #record}, freed in {@link #destroy} — and collects whatever state it needs itself (game state,
 * option values) rather than receiving it pushed in through the frame context.
 *
 * <p>What the engine still owns regardless: command-buffer submission, queue policy, stage ordering, the
 * lifetime of engine-declared {@link EngineImage} slots, and the layout contract on those slots at stage
 * entry/exit. Everything a pass allocates for itself, it manages itself — the engine does not track or
 * validate it.
 *
 * <p>A pass that throws from any of these methods is disabled with a logged error; the frame loop
 * continues without it.
 */
public interface CausticaRenderPass {
    Identifier id();

    RenderStage stage();

    /**
     * Ids of other passes in the same stage this pass must record after. Ignored for passes in a
     * different stage — stage order already implies it there. Defaults to no ordering constraint.
     */
    default List<Identifier> after() {
        return List.of();
    }

    /** Called once, after every pass has been registered and before the first frame. */
    default void create(PassSetup setup) {
    }

    /** Called whenever the display resolution changes, after {@link #create}. */
    default void resize(PassSetup setup, int displayWidth, int displayHeight) {
    }

    /** Called every frame, in stage/{@link #after()} order, to record this pass's work. */
    void record(PassFrame frame);

    /**
     * Called when persistent bake state a pass is holding onto (e.g. a "runs once" LUT) should be
     * considered stale — on a dimension change or a resource reload. A pass with no such state ignores
     * this; one that has it should redo the bake on its next {@link #record}.
     */
    default void invalidate() {
    }

    /** Called once, when this pass's feature is deselected or the engine shuts down. */
    default void destroy() {
    }
}
