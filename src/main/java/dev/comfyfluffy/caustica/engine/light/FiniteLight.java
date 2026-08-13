package dev.comfyfluffy.caustica.engine.light;

import java.util.Objects;

/**
 * Canonical finite-light metadata consumed by engine-owned spatial light structures.
 *
 * <p>Both photometric quantities are carried because they answer different questions.
 * {@code luminousPowerLumens} is the emitter's total output and is what a subtree's energy sums to;
 * {@code peakLuminousIntensityCandela} divided by squared metres is the peak illuminance the emitter can
 * deliver, which is the only scale on which the light-selection proposal can compare a rectangle, a
 * point, a spot and a distant source.
 */
public record FiniteLight(LightDescriptor.Finite descriptor, LightBvh.Aabb bounds,
                          LightBvh.OrientationCone orientation,
                          double luminousPowerLumens,
                          double peakLuminousIntensityCandela) {
    // ACEScg/AP1 luminance coefficients. This is the same Y used by the transport shaders.
    private static final double LUMA_R = 0.27222872;
    private static final double LUMA_G = 0.67408177;
    private static final double LUMA_B = 0.05368952;

    public FiniteLight {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(orientation, "orientation");
        if (!Double.isFinite(luminousPowerLumens) || luminousPowerLumens < 0.0) {
            throw new IllegalArgumentException("Light power must be finite and non-negative");
        }
        if (!Double.isFinite(peakLuminousIntensityCandela) || peakLuminousIntensityCandela < 0.0) {
            throw new IllegalArgumentException("Light intensity must be finite and non-negative");
        }
    }

    public static FiniteLight from(LightDescriptor.Finite descriptor, double metersPerWorldUnit) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!(metersPerWorldUnit > 0.0) || !Double.isFinite(metersPerWorldUnit)) {
            throw new IllegalArgumentException("metersPerWorldUnit must be finite and positive");
        }
        requireFinitePosition(descriptor);

        // Only a rectangle's photometry depends on the world scale: its power comes from an area
        // measured in scene units. Point and spot intensities are already absolute.
        return switch (descriptor) {
            case LightDescriptor.Rectangle rectangle -> rectangle(rectangle, metersPerWorldUnit);
            case LightDescriptor.Point point -> point(point);
            case LightDescriptor.Spot spot -> spot(spot);
        };
    }

    private static FiniteLight rectangle(LightDescriptor.Rectangle light,
                                         double metersPerWorldUnit) {
        requireFinite(light.halfUx(), light.halfUy(), light.halfUz(),
                light.halfVx(), light.halfVy(), light.halfVz(),
                light.normalX(), light.normalY(), light.normalZ(),
                light.radianceRedCdM2(), light.radianceGreenCdM2(),
                light.radianceBlueCdM2());
        requireNonNegative(light.radianceRedCdM2(), light.radianceGreenCdM2(),
                light.radianceBlueCdM2());

        double crossX = light.halfUy() * light.halfVz() - light.halfUz() * light.halfVy();
        double crossY = light.halfUz() * light.halfVx() - light.halfUx() * light.halfVz();
        double crossZ = light.halfUx() * light.halfVy() - light.halfUy() * light.halfVx();
        double areaWorldUnitsSquared = 4.0 * length(crossX, crossY, crossZ);
        if (!(areaWorldUnitsSquared > 0.0)) {
            throw new IllegalArgumentException("Rectangle light axes must span a non-zero area");
        }
        double[] normal = normalized(light.normalX(), light.normalY(), light.normalZ(),
                "Rectangle light normal");
        double crossLength = length(crossX, crossY, crossZ);
        double alignment = Math.abs((crossX * normal[0] + crossY * normal[1]
                + crossZ * normal[2]) / crossLength);
        if (alignment < 0.999999) {
            throw new IllegalArgumentException(
                    "Rectangle light normal must be parallel to its half-axis cross product");
        }
        double extentX = Math.abs(light.halfUx()) + Math.abs(light.halfVx());
        double extentY = Math.abs(light.halfUy()) + Math.abs(light.halfVy());
        double extentZ = Math.abs(light.halfUz()) + Math.abs(light.halfVz());
        LightBvh.Aabb bounds = LightBvh.Aabb.around(light.positionX(), light.positionY(),
                light.positionZ(), extentX, extentY, extentZ);
        double luminance = luminance(light.radianceRedCdM2(), light.radianceGreenCdM2(),
                light.radianceBlueCdM2());
        // A Lambertian emitter radiates its luminance times its metric area straight along the normal,
        // and its luminous exitance is pi times its luminance.
        double peakIntensity = luminance * areaWorldUnitsSquared
                * metersPerWorldUnit * metersPerWorldUnit;
        return new FiniteLight(light, bounds,
                new LightBvh.OrientationCone(normal[0], normal[1], normal[2], 0.0),
                Math.PI * peakIntensity, peakIntensity);
    }

    private static FiniteLight point(LightDescriptor.Point light) {
        requireFinite(light.rangeMeters(), light.intensityRedCandela(),
                light.intensityGreenCandela(), light.intensityBlueCandela());
        if (!(light.rangeMeters() > 0.0)) {
            throw new IllegalArgumentException("Point light range must be positive");
        }
        requireNonNegative(light.intensityRedCandela(), light.intensityGreenCandela(),
                light.intensityBlueCandela());
        LightBvh.Aabb bounds = LightBvh.Aabb.point(light.positionX(), light.positionY(),
                light.positionZ());
        double intensity = luminance(light.intensityRedCandela(),
                light.intensityGreenCandela(), light.intensityBlueCandela());
        return new FiniteLight(light, bounds, LightBvh.OrientationCone.omnidirectional(),
                4.0 * Math.PI * intensity, intensity);
    }

    private static FiniteLight spot(LightDescriptor.Spot light) {
        requireFinite(light.directionX(), light.directionY(), light.directionZ(),
                light.rangeMeters(), light.outerHalfAngleRadians(),
                light.intensityRedCandela(), light.intensityGreenCandela(),
                light.intensityBlueCandela());
        if (!(light.rangeMeters() > 0.0)) {
            throw new IllegalArgumentException("Spot light range must be positive");
        }
        if (!(light.outerHalfAngleRadians() > 0.0)
                || light.outerHalfAngleRadians() > Math.PI) {
            throw new IllegalArgumentException("Spot light half-angle must be in (0, pi]");
        }
        requireNonNegative(light.intensityRedCandela(), light.intensityGreenCandela(),
                light.intensityBlueCandela());
        double[] direction = normalized(light.directionX(), light.directionY(), light.directionZ(),
                "Spot light direction");
        LightBvh.Aabb bounds = LightBvh.Aabb.point(light.positionX(), light.positionY(),
                light.positionZ());
        double solidAngle = 2.0 * Math.PI * (1.0 - Math.cos(light.outerHalfAngleRadians()));
        double intensity = luminance(light.intensityRedCandela(),
                light.intensityGreenCandela(), light.intensityBlueCandela());
        return new FiniteLight(light, bounds,
                new LightBvh.OrientationCone(direction[0], direction[1], direction[2],
                        light.outerHalfAngleRadians()), solidAngle * intensity, intensity);
    }

    private static double luminance(double red, double green, double blue) {
        return Math.max(0.0, LUMA_R * red + LUMA_G * green + LUMA_B * blue);
    }

    private static void requireFinitePosition(LightDescriptor.Finite light) {
        requireFinite(light.positionX(), light.positionY(), light.positionZ());
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

    private static double[] normalized(double x, double y, double z, String name) {
        double length = length(x, y, z);
        if (!(length > 0.0)) throw new IllegalArgumentException(name + " must be non-zero");
        return new double[]{x / length, y / length, z / length};
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
