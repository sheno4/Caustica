package dev.comfyfluffy.caustica.api.scene.light;

import java.util.Objects;

/**
 * One light, in scene coordinates and the photometric units named per shape.
 *
 * <p><b>Shape and emission are separate.</b> The renderer owns the geometric and sampling contract for
 * every primitive here: it chooses candidates, samples positions or directions, and computes the PDF the
 * estimator uses. An extension never replaces those, for the reason it never replaces the OpenPBR BSDF —
 * independently implemented sampling and evaluation can disagree and bias every lighting backend at once.
 *
 * <p>What an extension can replace is the emitted value, through an {@link Emission} profile. Sampling
 * stays that of the underlying primitive, so a concentrated profile can raise variance but cannot
 * invalidate the PDF.
 *
 * <p>A new emitter <em>shape</em> — a tube, a line, a volumetric beam — changes how the renderer samples
 * light and is an engine feature, not an extension one. Triangle lights, if they arrive, arrive as their
 * own retained list rather than as a field on scene geometry.
 */
public sealed interface LightDescriptor {

    /** The optional profile authoring this light's emitted value, or null for the descriptor's own. */
    Emission emission();

    /**
     * A Slang emission profile and its per-light word.
     *
     * <p>The profile computes the final scene-linear ACEScg emitted value from renderer-produced facts —
     * direction leaving the light, distance, cone cosine, projected coordinates — plus {@code parameters},
     * which is uninterpreted and wide enough to be a device address. It may read anything the same feature
     * published, and may ignore the descriptor's own colour entirely. Dynamic data stays behind a stable
     * address and is updated by GPU commands recorded before tracing, not by an unsynchronized host write.
     *
     * <p>{@code peak*} is a correctness contract wherever the renderer culls on it: every value the profile
     * can produce while this descriptor is retained must stay within it. Changing resources beyond that
     * bound requires a new {@code Put}. {@code averagePower} is only a proposal weight for light selection
     * — a wrong value costs variance but must never remove the light from sampling support.
     */
    record Emission(EmissionProfileId profile, long parameters,
                    double peakRed, double peakGreen, double peakBlue,
                    double averagePower) {
        public Emission {
            Objects.requireNonNull(profile, "profile");
            if (peakRed < 0.0 || peakGreen < 0.0 || peakBlue < 0.0 || averagePower < 0.0) {
                throw new IllegalArgumentException("emission bounds must be non-negative");
            }
        }
    }

    /** A spatial emitter eligible for finite-light acceleration structures. */
    sealed interface Finite extends LightDescriptor {
        double positionX();

        double positionY();

        double positionZ();
    }

    /** A one-sided rectangular emitter; radiance is scene-linear ACEScg in cd/m². */
    record Rectangle(double positionX, double positionY, double positionZ,
                     double halfUx, double halfUy, double halfUz,
                     double halfVx, double halfVy, double halfVz,
                     double normalX, double normalY, double normalZ,
                     double radianceRedCdM2, double radianceGreenCdM2, double radianceBlueCdM2,
                     Emission emission) implements Finite {
    }

    /** An omnidirectional point emitter; range is a finite influence bound in metres, intensity in candela. */
    record Point(double positionX, double positionY, double positionZ,
                 double rangeMeters,
                 double intensityRedCandela, double intensityGreenCandela, double intensityBlueCandela,
                 Emission emission) implements Finite {
    }

    /**
     * A cone emitter, in candela over a range in metres.
     *
     * <p>Orientation is complete rather than a bare direction: a profile that varies in two dimensions —
     * a gobo, a projected texture — needs a stable roll axis for the renderer to produce projected
     * coordinates from. Separate horizontal and vertical half-angles define the projection and its aspect;
     * a plain radial falloff can ignore both and use the cone cosine.
     */
    record Spot(double positionX, double positionY, double positionZ,
                double directionX, double directionY, double directionZ,
                double upX, double upY, double upZ,
                double rangeMeters,
                double horizontalHalfAngleRadians, double verticalHalfAngleRadians,
                double intensityRedCandela, double intensityGreenCandela, double intensityBlueCandela,
                Emission emission) implements Finite {
    }

    /**
     * A distant angular emitter. Direction points toward the source, illuminance is total scene-linear
     * ACEScg normal illuminance in lux, and angular radius is the sampling cone's half-angle; zero is an
     * exact directional source.
     */
    record Distant(double directionX, double directionY, double directionZ,
                   double illuminanceRedLux, double illuminanceGreenLux, double illuminanceBlueLux,
                   double angularRadiusRadians,
                   Emission emission) implements LightDescriptor {
    }
}
