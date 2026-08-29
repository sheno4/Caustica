package dev.comfyfluffy.caustica.api.light;

final class LightValidation {
    static final double UNIT_TOLERANCE = 1.0e-5;

    private LightValidation() { }

    static void position(double x, double y, double z) {
        finite(x, "positionX");
        finite(y, "positionY");
        finite(z, "positionZ");
    }

    static void color(double red, double green, double blue, String name) {
        nonnegative(red, name + " red");
        nonnegative(green, name + " green");
        nonnegative(blue, name + " blue");
    }

    static void unit(double x, double y, double z, String name) {
        finite(x, name + " X");
        finite(y, name + " Y");
        finite(z, name + " Z");
        double squaredLength = x * x + y * y + z * z;
        if (!Double.isFinite(squaredLength) || Math.abs(squaredLength - 1.0) > UNIT_TOLERANCE) {
            throw new IllegalArgumentException(name + " must be normalized");
        }
    }

    static void nonzero(double x, double y, double z, String name) {
        finite(x, name + " X");
        finite(y, name + " Y");
        finite(z, name + " Z");
        double squaredLength = x * x + y * y + z * z;
        if (!Double.isFinite(squaredLength) || squaredLength <= 0.0) {
            throw new IllegalArgumentException(name + " must be nondegenerate");
        }
    }

    static void positive(double value, String name) {
        finite(value, name);
        if (value <= 0.0) throw new IllegalArgumentException(name + " must be positive");
    }

    static void halfAngle(double value, String name, boolean allowZero) {
        finite(value, name);
        if ((allowZero ? value < 0.0 : value <= 0.0) || value >= Math.PI / 2.0) {
            throw new IllegalArgumentException(name + " must be "
                    + (allowZero ? "in [0, pi/2)" : "in (0, pi/2)"));
        }
    }

    private static void nonnegative(double value, String name) {
        finite(value, name);
        if (value < 0.0) throw new IllegalArgumentException(name + " must be non-negative");
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
