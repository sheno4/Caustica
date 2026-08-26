package dev.comfyfluffy.caustica.api.ui;

import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import org.joml.Matrix4fc;

/**
 * Passed to {@link UiPass#record} to draw one frame's UI layer.
 *
 * <p>Nothing the world pipeline produces is reachable from here, and that is deliberate: this runs after
 * the display transform, so there is no acceleration structure, no exposure, and no scene-linear colour to
 * read. A pass that needs any of those belongs in the post-effects chain instead.
 *
 * <p>The camera is the exception, because world-anchored UI is still UI — see
 * {@link #worldViewProjection()}.
 */
public interface UiFrame extends PassFrame {
    /**
     * The layer this pass draws into, sized to the display and cleared to transparent black once per frame
     * before the first pass runs. Passes compose onto it in registration order, so it holds whatever
     * earlier passes already drew.
     *
     * <p>sRGB with <b>premultiplied</b> alpha. Draw with the ordinary straight-alpha "over" blend
     * ({@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} for colour and {@code ONE, ONE_MINUS_SRC_ALPHA} for alpha)
     * and the layer accumulates premultiplied on its own; a pass that samples the layer, or that draws
     * content it has already premultiplied itself, must use the premultiplied-over blend instead.
     *
     * <p>The engine composites the finished layer during present and handles the display mode: a
     * premultiplied blend over the tone-mapped image on SDR, the same blend through a PQ conversion on HDR.
     * A pass never learns which, and never writes anything but sRGB.
     */
    GpuImage layer();

    /**
     * Unjittered world view-projection for the frame this layer is being drawn for. World-anchored UI —
     * selection boxes, entity outlines, name tags, waypoint markers — projects with this, so its pixels
     * land on the same screen position the traced world put that geometry at.
     *
     * <p>It is the <em>rendered</em> frame's camera; no new camera or UI invocation exists for a generated
     * frame. A frame-generation backend may reuse or separately interpolate the recorded layer, so exact
     * alignment with the generated scene is not guaranteed. Anchor UI to the world only where that
     * mismatch is acceptable — never for a reticle. Do not retain or mutate the matrix.
     */
    Matrix4fc worldViewProjection();

    int displayWidth();

    int displayHeight();

    /** The sRGB VkFormat the layer is created with, for building a pipeline compatible with it. */
    int layerFormat();

}
