package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuImage;

/**
 * Frame capabilities for a post-effect registration, adding what the post chain produces for it to read
 * and the target that enrols it.
 *
 * <p>Both images are display-resolution and carry their own extent, so there is no separate display size
 * to read here and no way for the two to disagree.
 */
public interface PostEffectFrame extends PassFrame {
    /**
     * The scene as it stands at this point in the chain: scene-linear ACEScg, not yet exposed, look
     * transformed, or tone mapped. For the first pass that acquires an output it is the reconstructed colour
     * after DLSS-RR (or the no-RR blit); after that it is whatever the previous participating pass wrote.
     *
     * <p>Engine-produced and resolved fresh every frame — never cache the returned {@link GpuImage} across
     * frames, since a resize recreates it.
     */
    GpuImage sceneColor();

    /**
     * Join the chain: the image this pass writes its version of the scene into. Calling this explicit
     * acquisition method is what
     * enrols the pass in the ordered post-effect chain.
     *
     * <p>Always distinct from {@link #sceneColor()}, so write every pixel, including the ones the effect
     * does not change: the target holds the previous frame's chain contents, not a copy of the source.
     * That separation is what lets an effect gather from neighbouring pixels safely, which reading and
     * writing one image could not.
     *
     * <p>Two outputs rotate, so calling this twice in one {@code record} returns the same image; a pass
     * needing its own intermediates allocates them itself.
     */
    GpuImage acquireSceneColorOutput();

    /** This frame's scalar exposure. Engine-produced and resolved fresh every frame, as above. */
    GpuImage exposureImage();
}
