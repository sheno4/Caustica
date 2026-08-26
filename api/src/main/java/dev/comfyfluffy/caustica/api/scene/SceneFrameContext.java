package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.CausticaApi;

import java.util.Objects;

/**
 * Renderer-neutral inputs shared by every source during one frame.
 *
 * <p>The context names <b>one scene</b> — the one being traced, with the camera and rebase origin that
 * belong to it. Frame-coherent submission is therefore scoped to that scene: a source builds its
 * view-dependent content for this camera and places it here. Content in a scene this context does not name
 * is not view-dependent with respect to it, and goes through {@link CausticaApi#geometry()} on the source's
 * own schedule like everything else.
 *
 * <p>There is no sink here. Frame coherence comes from <em>when</em> you call, not from a second channel:
 * submissions made during {@link SceneProvider#submitGeometry} land in this frame, everything else lands
 * as soon as the renderer can. So a source calls {@link CausticaApi#geometry()} exactly as it does anywhere
 * else.
 */
public record SceneFrameContext(SceneId scene,
                                double originX, double originY, double originZ,
                                long frameIndex,
                                SceneCamera camera) {
    public SceneFrameContext {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(camera, "camera");
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("scene origin must be finite");
        }
    }
}
