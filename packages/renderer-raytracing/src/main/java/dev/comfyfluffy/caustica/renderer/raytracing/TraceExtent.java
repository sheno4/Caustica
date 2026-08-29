package dev.comfyfluffy.caustica.renderer.raytracing;

/** Render and display extents for one trace-resource allocation. */
public record TraceExtent(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
    public TraceExtent {
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("Trace extents must be positive");
        }
    }
}
