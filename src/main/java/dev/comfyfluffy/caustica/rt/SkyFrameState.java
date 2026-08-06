package dev.comfyfluffy.caustica.rt;

/**
 * Holds the current frame's {@link SkyFrame}, computed once by {@code RtComposite} and pulled directly by
 * any pass that needs it (e.g. {@code SkyLutPass}) rather than having it pushed through the frame context.
 */
public final class SkyFrameState {
    private static volatile SkyFrame current;

    private SkyFrameState() {
    }

    public static SkyFrame current() {
        SkyFrame frame = current;
        if (frame == null) {
            throw new IllegalStateException("SkyFrameState read before the first frame computed one");
        }
        return frame;
    }

    public static void set(SkyFrame frame) {
        current = frame;
    }
}
