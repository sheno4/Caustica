package dev.comfyfluffy.caustica.api.ui;

import dev.comfyfluffy.caustica.api.pass.PassLifecycle;
import dev.comfyfluffy.caustica.api.pass.PostEffectPass;

/**
 * A unit of UI drawing, composited after the display transform and before present.
 *
 * <p>Separate from the world stages because it must remain outside the scene input frame generation
 * interpolates. This pass records once per <em>rendered</em> frame. Presentation consumes its layer for
 * every output frame; a frame-generation backend may reuse it or interpolate it separately from the
 * HUD-less scene before recompositing the two.
 *
 * <p>The practical consequence for an implementation: <b>there is no {@link #record} invocation for a
 * generated frame, and one recorded layer may contribute to multiple presented frames.</b> Do not depend
 * on state that a frame-generation backend could not reuse or interpolate separately.
 *
 * <p><b>World-anchored overlays are UI, not post effects.</b> Selection boxes, entity outlines, name tags,
 * leashes, waypoint markers — anything drawn at a world position but authored as crisp 2D — belongs here
 * even though it is positioned by the camera ({@link UiFrame#worldViewProjection()}). Two reasons, and
 * either alone is sufficient: thin high-contrast geometry does not survive a temporal upscaler, so it must
 * be drawn at display resolution after reconstruction rather than traced or rastered at render resolution;
 * and it must not be embedded in the scene input that frame generation interpolates. A
 * {@link PostEffectPass} fails both. What belongs in the post chain is an effect on the scene image itself.
 */
public interface UiPass extends PassLifecycle {
    /** Draw this pass's contribution onto {@link UiFrame#layer()}. */
    void record(UiFrame frame);
}
