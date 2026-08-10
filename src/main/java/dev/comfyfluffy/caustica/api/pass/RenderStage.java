package dev.comfyfluffy.caustica.api.pass;

/**
 * Coarse frame phases a pass records into. Declaration order is recording order: passes in an earlier
 * stage always record before passes in a later stage. Within one stage, ties are broken by
 * {@link CausticaRenderPass#after()}, then by {@link CausticaRenderPass#id()} for determinism.
 *
 * <p>The bracketed anchors below are engine-owned, not stages a pass can select — they mark where the
 * ray-traced world pipeline, DLSS-RR, and the swapchain present sit relative to the stages a pass can
 * record into.
 */
public enum RenderStage {
    /** Once-per-frame environment bakes an extension's own trace depends on (e.g. sky LUTs). */
    ENVIRONMENT_PREPARE,
    /** Scene contribution: TLAS/light/material updates for the frame about to trace. */
    SCENE_PREPARE,
    /** Last chance to touch state the world pipeline reads before it dispatches. */
    BEFORE_TRACE,
    // ⟦ world pipeline: primary + indirect trace, engine-owned ⟧
    /** Runs on the raw trace output before reconstruction, e.g. a guide-buffer fixup. */
    AFTER_TRACE,
    // ⟦ DLSS-RR reconstruction, engine-owned ⟧
    /** Post-reconstruction compositing over the reconstructed colour target (bloom lives here). */
    AFTER_RECONSTRUCTION,
    /** Histogram / auto-exposure resolve, after reconstruction and before the look stage reads it. */
    EXPOSURE,
    /** Tone mapping and display-referred look transforms. */
    LOOK,
    /** Host UI and other screen-space overlays. */
    OVERLAY,
    /** Final blit/composite immediately before presentation. */
    PRESENT
}
