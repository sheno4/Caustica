package dev.comfyfluffy.caustica.api.light;

/**
 * One engine-sampled light. Positions and rectangle axes use the target scene's coordinate units; fields
 * explicitly named in metres remain physical. Colours use scene-linear ACEScg and every vector named as a
 * direction or normal is unit length. The supported light types are {@link Rectangle}, {@link Point},
 * {@link Spot}, and {@link Distant}.
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

    /** An omnidirectional point emitter; range is in metres and intensity is in candela. */
    record Point(double positionX, double positionY, double positionZ,
                 double rangeMeters,
                 double intensityRedCandela, double intensityGreenCandela, double intensityBlueCandela)
            implements Finite {
        public Point {
            LightValidation.position(positionX, positionY, positionZ);
            LightValidation.positive(rangeMeters, "rangeMeters");
            LightValidation.color(intensityRedCandela, intensityGreenCandela, intensityBlueCandela,
                    "intensity");
        }
    }

    /**
     * A cone emitter. Direction and up are unit, mutually perpendicular vectors. Separate horizontal and
     * vertical half-angles describe an elliptical cone and give it a stable roll orientation.
     */
    record Spot(double positionX, double positionY, double positionZ,
                double directionX, double directionY, double directionZ,
                double upX, double upY, double upZ,
                double rangeMeters,
                double horizontalHalfAngleRadians, double verticalHalfAngleRadians,
                double intensityRedCandela, double intensityGreenCandela, double intensityBlueCandela)
            implements Finite {
        public Spot {
            LightValidation.position(positionX, positionY, positionZ);
            LightValidation.unit(directionX, directionY, directionZ, "spot direction");
            LightValidation.unit(upX, upY, upZ, "spot up");
            double dot = directionX * upX + directionY * upY + directionZ * upZ;
            if (!Double.isFinite(dot) || Math.abs(dot) > LightValidation.UNIT_TOLERANCE) {
                throw new IllegalArgumentException("spot direction and up must be perpendicular");
            }
            LightValidation.positive(rangeMeters, "rangeMeters");
            LightValidation.halfAngle(horizontalHalfAngleRadians, "horizontalHalfAngleRadians", false);
            LightValidation.halfAngle(verticalHalfAngleRadians, "verticalHalfAngleRadians", false);
            LightValidation.color(intensityRedCandela, intensityGreenCandela, intensityBlueCandela,
                    "intensity");
        }
    }

    /**
     * A distant angular emitter. Direction points toward the source, illuminance is in lux, and zero
     * angular radius is an exact directional source.
     */
    record Distant(double directionX, double directionY, double directionZ,
                   double illuminanceRedLux, double illuminanceGreenLux, double illuminanceBlueLux,
                   double angularRadiusRadians) implements LightDescriptor {
        public Distant {
            LightValidation.unit(directionX, directionY, directionZ, "distant direction");
            LightValidation.color(illuminanceRedLux, illuminanceGreenLux, illuminanceBlueLux,
                    "illuminance");
            LightValidation.halfAngle(angularRadiusRadians, "angularRadiusRadians", true);
        }
    }
}
