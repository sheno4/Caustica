package dev.comfyfluffy.caustica.engine.light;

/** Host-supplied light description using scene coordinates and the photometric units named below. */
public sealed interface LightDescriptor {
    long key();

    /** A spatial emitter eligible for finite-light acceleration structures. */
    sealed interface Finite extends LightDescriptor permits Rectangle, Point, Spot {
        double positionX();

        double positionY();

        double positionZ();
    }

    /** A one-sided rectangular emitter with scene-linear ACEScg radiance in cd/m². */
    record Rectangle(long key,
                     double positionX, double positionY, double positionZ,
                     double halfUx, double halfUy, double halfUz,
                     double halfVx, double halfVy, double halfVz,
                     double normalX, double normalY, double normalZ,
                     double radianceRedCdM2, double radianceGreenCdM2,
                     double radianceBlueCdM2) implements Finite {
    }

    /**
     * An omnidirectional point emitter. Range is a finite influence bound in meters and intensity is
     * scene-linear ACEScg luminous intensity in candela.
     */
    record Point(long key,
                 double positionX, double positionY, double positionZ,
                 double rangeMeters,
                 double intensityRedCandela, double intensityGreenCandela,
                 double intensityBlueCandela) implements Finite {
    }

    /**
     * A constant-intensity cone emitter. Range is in meters, the direction is in scene coordinates,
     * the outer half-angle is in radians, and intensity is scene-linear ACEScg candela.
     */
    record Spot(long key,
                double positionX, double positionY, double positionZ,
                double directionX, double directionY, double directionZ,
                double rangeMeters, double outerHalfAngleRadians,
                double intensityRedCandela, double intensityGreenCandela,
                double intensityBlueCandela) implements Finite {
    }

    /**
     * A distant angular emitter. Direction points toward the source, illuminance is the total
     * scene-linear ACEScg normal illuminance integrated over the source in lux, and angular radius is
     * the sampling cone's half-angle in radians. A zero radius is an exact directional source.
     */
    record Distant(long key,
                   double directionX, double directionY, double directionZ,
                   double illuminanceRedLux, double illuminanceGreenLux,
                   double illuminanceBlueLux, double angularRadiusRadians) implements LightDescriptor {
    }
}
