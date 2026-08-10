package dev.comfyfluffy.caustica.engine.light;

import java.util.Objects;

/** Canonical metadata for a distant light, kept outside finite-light spatial structures. */
public record DistantLight(LightDescriptor.Distant descriptor,
                           double directionX, double directionY, double directionZ) {
    public DistantLight {
        Objects.requireNonNull(descriptor, "descriptor");
        requireFinite(descriptor.directionX(), descriptor.directionY(), descriptor.directionZ(),
                descriptor.illuminanceRedLux(), descriptor.illuminanceGreenLux(),
                descriptor.illuminanceBlueLux(), descriptor.angularRadiusRadians());
        requireNonNegative(descriptor.illuminanceRedLux(), descriptor.illuminanceGreenLux(),
                descriptor.illuminanceBlueLux());
        if (descriptor.angularRadiusRadians() < 0.0
                || descriptor.angularRadiusRadians() > Math.PI * 0.5) {
            throw new IllegalArgumentException("Distant light angular radius must be in [0, pi/2]");
        }
        double length = Math.sqrt(descriptor.directionX() * descriptor.directionX()
                + descriptor.directionY() * descriptor.directionY()
                + descriptor.directionZ() * descriptor.directionZ());
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Distant light direction must be non-zero");
        }
        directionX = descriptor.directionX() / length;
        directionY = descriptor.directionY() / length;
        directionZ = descriptor.directionZ() / length;
    }

    public static DistantLight from(LightDescriptor.Distant descriptor) {
        return new DistantLight(descriptor, descriptor.directionX(), descriptor.directionY(),
                descriptor.directionZ());
    }

    private static void requireFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Light descriptor values must be finite");
            }
        }
    }

    private static void requireNonNegative(double... values) {
        for (double value : values) {
            if (value < 0.0) {
                throw new IllegalArgumentException("Photometric values must be non-negative");
            }
        }
    }
}
