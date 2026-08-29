package dev.comfyfluffy.caustica.rt.pipeline;

/** Sub-pixel Halton jitter in render-pixel space for temporal reconstruction. */
public final class RtJitter {
    private int frameIndex;
    private float pixelsX;
    private float pixelsY;

    public RtJitter() {
    }

    public void prepare(int renderWidth, int renderHeight, int displayWidth) {
        int phaseCount = jitterPhaseCount(renderWidth, displayWidth);
        int index = (frameIndex++ % phaseCount) + 1;
        pixelsX = halton(index, 2) - 0.5f;
        pixelsY = halton(index, 3) - 0.5f;
    }

    public float jitterPixelsX() {
        return pixelsX;
    }

    public float jitterPixelsY() {
        return pixelsY;
    }

    static int jitterPhaseCount(int renderWidth, int displayWidth) {
        float ratio = (float) displayWidth / Math.max(1, renderWidth);
        return Math.max(32, (int) Math.ceil(8.0f * ratio * ratio));
    }

    private static float halton(int index, int base) {
        float fraction = 1.0f;
        float result = 0.0f;
        while (index > 0) {
            fraction /= base;
            result += fraction * (index % base);
            index /= base;
        }
        return result;
    }
}
