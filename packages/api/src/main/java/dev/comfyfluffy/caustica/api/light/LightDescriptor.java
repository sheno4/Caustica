package dev.comfyfluffy.caustica.api.light;

/**
 * One engine-sampled light. Positions and rectangle axes use the target scene's coordinate units; fields
 * explicitly named in metres remain physical. Colours use scene-linear ACEScg and every vector named as a
 * direction or normal is unit length. The supported light types are {@link Rectangle}, {@link Spot}, and
 * {@link Distant}.
 */
public sealed interface LightDescriptor {

    /** A spatial emitter eligible for finite-light acceleration structures. */
    sealed interface Finite extends LightDescriptor {
        double positionX();
        double positionY();
        double positionZ();
    }

    /**
     * A one-sided rectangular emitter whose emitting normal is {@code normalize(halfU × halfV)};
     * radiance is in cd/m².
     */
    record Rectangle(double positionX, double positionY, double positionZ,
                     double halfUx, double halfUy, double halfUz,
                     double halfVx, double halfVy, double halfVz,
                     double radianceRedCdM2, double radianceGreenCdM2, double radianceBlueCdM2)
            implements Finite {
        public Rectangle {
            LightValidation.position(positionX, positionY, positionZ);
            LightValidation.nonzero(halfUx, halfUy, halfUz, "rectangle half-U axis");
            LightValidation.nonzero(halfVx, halfVy, halfVz, "rectangle half-V axis");
            double cx = halfUy * halfVz - halfUz * halfVy;
            double cy = halfUz * halfVx - halfUx * halfVz;
            double cz = halfUx * halfVy - halfUy * halfVx;
            LightValidation.nonzero(cx, cy, cz, "rectangle axes");
            LightValidation.color(radianceRedCdM2, radianceGreenCdM2, radianceBlueCdM2, "radiance");
        }
    }

    /**
     * A circular cone emitter. Direction is unit length, range is in metres, the half-angle is in radians,
     * and intensity is in candela.
     */
    record Spot(double positionX, double positionY, double positionZ,
                double directionX, double directionY, double directionZ,
                double rangeMeters,
                double halfAngleRadians,
                double intensityRedCandela, double intensityGreenCandela, double intensityBlueCandela)
            implements Finite {
        public Spot {
            LightValidation.position(positionX, positionY, positionZ);
            LightValidation.unit(directionX, directionY, directionZ, "spot direction");
            LightValidation.positive(rangeMeters, "rangeMeters");
            LightValidation.halfAngle(halfAngleRadians, "halfAngleRadians", false);
            LightValidation.color(intensityRedCandela, intensityGreenCandela, intensityBlueCandela,
                    "intensity");
        }
    }

    /**
     * A distant angular emitter. Direction points toward the source, illuminance is in lux, and zero
     * angular radius is an exact directional source. {@code environmentEmitter} declares that the
     * environment draws the same source, allowing non-camera miss rays to suppress that duplicate estimator.
     */
    record Distant(double directionX, double directionY, double directionZ,
                   double illuminanceRedLux, double illuminanceGreenLux, double illuminanceBlueLux,
                   double angularRadiusRadians, boolean environmentEmitter) implements LightDescriptor {
        public Distant {
            LightValidation.unit(directionX, directionY, directionZ, "distant direction");
            LightValidation.color(illuminanceRedLux, illuminanceGreenLux, illuminanceBlueLux,
                    "illuminance");
            LightValidation.halfAngle(angularRadiusRadians, "angularRadiusRadians", true);
        }
    }
}
