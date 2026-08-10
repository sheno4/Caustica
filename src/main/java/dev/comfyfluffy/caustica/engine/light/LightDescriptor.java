package dev.comfyfluffy.caustica.engine.light;

/**
 * Host-supplied description of a finite light. Positions and geometric vectors are in scene world
 * units; photometric values use the units named by each record. The engine applies the scene's
 * meters-per-world-unit scale when it creates a {@link FiniteLight}.
 */
public sealed interface LightDescriptor {
    long key();

    double positionX();

    double positionY();

    double positionZ();

    /** A one-sided rectangular emitter with scene-linear ACEScg radiance in cd/m². */
    record Rectangle(long key,
                     double positionX, double positionY, double positionZ,
                     double halfUx, double halfUy, double halfUz,
                     double halfVx, double halfVy, double halfVz,
                     double normalX, double normalY, double normalZ,
                     double radianceRedCdM2, double radianceGreenCdM2,
                     double radianceBlueCdM2) implements LightDescriptor {
    }

    /**
     * An omnidirectional point emitter. Range is a finite influence bound in meters and intensity is
     * scene-linear ACEScg luminous intensity in candela.
     */
    record Point(long key,
                 double positionX, double positionY, double positionZ,
                 double rangeMeters,
                 double intensityRedCandela, double intensityGreenCandela,
                 double intensityBlueCandela) implements LightDescriptor {
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
                double intensityBlueCandela) implements LightDescriptor {
    }
}
