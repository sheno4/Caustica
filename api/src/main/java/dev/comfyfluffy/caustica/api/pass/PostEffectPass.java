package dev.comfyfluffy.caustica.api.pass;

/**
 * A pass that transforms the scene image, recorded after reconstruction and before the display transform.
 * Bloom, a colour grade, a lens effect, a depth-of-field.
 *
 * <p>The renderer knows two things about such a pass: that it records after reconstruction, and whether it
 * took a target this frame ({@link PostEffectFrame#sceneColorTarget()}). Taking one enrols the pass in the
 * chain — the engine barriers after it and hands what it wrote to the next pass's
 * {@link PostEffectFrame#sceneColor()}, with the display transform reading whatever the last one produced.
 * A pass that does not take one leaves the chain untouched and costs it nothing, which is how an effect
 * switches itself off for a frame.
 *
 * <p>Everything else — the shader, the pipeline, the intermediate images, the dispatch — is the
 * extension's. The renderer never inspects what happened between the two images.
 *
 * <p>Passes record in registration order, and unlike the pre-trace stage that order is meaningful: it is
 * the order effects compose in. It is still not a dependency mechanism — a pass must work correctly when
 * the passes around it are absent.
 */
public interface PostEffectPass extends PassLifecycle {
    /** Record this pass's work for one frame. */
    void record(PostEffectFrame frame);
}
