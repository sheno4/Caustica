package dev.comfyfluffy.caustica.engine.scene;

/** Double-precision world-space origin used to keep GPU scene coordinates near zero. */
public record SceneOrigin(double x, double y, double z) {
    public static final SceneOrigin ZERO = new SceneOrigin(0.0, 0.0, 0.0);

    public float relativeX(double worldX) {
        return relative(worldX, x);
    }

    public float relativeY(double worldY) {
        return relative(worldY, y);
    }

    public float relativeZ(double worldZ) {
        return relative(worldZ, z);
    }

    /** Wrap the X origin into {@code [0, period)} for a precision-safe procedural domain anchor. */
    public float wrappedX(double period) {
        return wrapped(x, period);
    }

    /** Wrap the Y origin into {@code [0, period)} for a precision-safe procedural domain anchor. */
    public float wrappedY(double period) {
        return wrapped(y, period);
    }

    /** Wrap the Z origin into {@code [0, period)} for a precision-safe procedural domain anchor. */
    public float wrappedZ(double period) {
        return wrapped(z, period);
    }

    private static float relative(double world, double origin) {
        return (float) (world - origin);
    }

    private static float wrapped(double value, double period) {
        return (float) (value - Math.floor(value / period) * period);
    }
}
