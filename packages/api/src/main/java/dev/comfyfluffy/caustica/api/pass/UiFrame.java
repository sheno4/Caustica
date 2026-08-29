package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;

/**
 * Passed to a UI-stage {@link Pass} to draw one rendered frame's UI layer. There is no recording callback
 * for a generated frame; presentation may reuse or separately interpolate the recorded layer.
 *
 * <p>Scene-linear colour and exposure are not reachable here. The only borrowed world resource is the
 * entry scene's TLAS for occlusion ray queries by world-anchored overlays.
 *
 * <p>The camera is the exception, because world-anchored UI is still UI — see
 * {@link #worldViewProjection()}.
 */
public interface UiFrame extends PassFrame {
    /**
     * The layer this pass draws into, sized to the display and cleared to transparent black once per frame
     * before the first pass runs. Passes compose onto it in their constrained stage order, so it holds
     * whatever earlier passes already drew.
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
     * mismatch is acceptable — never for a reticle. The returned 16 column-major floats are an immutable
     * snapshot: implementations return a defensive copy, and callers do not retain or mutate it.
     */
    float[] worldViewProjection();

    /**
     * Shader-visible descriptor for {@link #view()}'s entry-scene TLAS. Pass the descriptor index to the
     * UI shader and resolve it as a {@code RaytracingAccelerationStructure}; the engine supplies the build-to-
     * shader barrier and retains both the descriptor and TLAS through this frame's GPU completion.
     */
    GpuAccelerationStructureDescriptor entrySceneTlasDescriptor();

}
