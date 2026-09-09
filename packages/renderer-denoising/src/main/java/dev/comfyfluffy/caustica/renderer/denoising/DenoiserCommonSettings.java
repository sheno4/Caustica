package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/**
 * Camera and temporal state shared by the denoisers for one frame. Matrices are column-major;
 * construction and access copy them so caller mutations cannot change captured settings.
 */
public record DenoiserCommonSettings(
        float[] worldToView,
        float[] worldToViewPrevious,
        float[] viewToClip,
        float[] viewToClipPrevious,
        float jitterX,
        float jitterY,
        float previousJitterX,
        float previousJitterY,
        float motionScaleX,
        float motionScaleY,
        float motionScaleZ,
        float denoisingRange,
        float disocclusionThreshold,
        float alternateDisocclusionThreshold,
        float frameTimeMilliseconds,
        int frameIndex,
        boolean motionInWorldSpace,
        DenoiserReset reset) {
    public DenoiserCommonSettings {
        worldToView = matrix(worldToView, "worldToView");
        worldToViewPrevious = matrix(worldToViewPrevious, "worldToViewPrevious");
        viewToClip = matrix(viewToClip, "viewToClip");
        viewToClipPrevious = matrix(viewToClipPrevious, "viewToClipPrevious");
        if (!(denoisingRange > 0.0f)) throw new IllegalArgumentException("denoisingRange must be positive");
        if (!(frameTimeMilliseconds >= 0.0f)) throw new IllegalArgumentException("frameTimeMilliseconds must be non-negative");
        reset = Objects.requireNonNull(reset, "reset");
    }

    @Override public float[] worldToView() { return worldToView.clone(); }
    @Override public float[] worldToViewPrevious() { return worldToViewPrevious.clone(); }
    @Override public float[] viewToClip() { return viewToClip.clone(); }
    @Override public float[] viewToClipPrevious() { return viewToClipPrevious.clone(); }

    private static float[] matrix(float[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != 16) throw new IllegalArgumentException(name + " must contain 16 floats");
        return value.clone();
    }
}
