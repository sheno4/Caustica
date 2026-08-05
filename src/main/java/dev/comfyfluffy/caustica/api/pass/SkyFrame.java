package dev.comfyfluffy.caustica.api.pass;

/** Semantic sky inputs shared by the world shader and environment preparation passes. */
public record SkyFrame(
        float sunAngleRadians,
        float moonAngleRadians,
        float starAngleRadians,
        float starBrightness,
        float sunIlluminanceLux,
        float moonIlluminanceLux,
        float nightAirglowLuminance,
        float starLuminance,
        float noonTiltRadians,
        float sunAngularRadiusRadians,
        float moonAngularRadiusRadians,
        float moonPhaseFixedFraction,
        float sunDiscHalfAngleRadians,
        float moonDiscHalfAngleRadians,
        float viewerAltitudeKm,
        float moonPhaseIndex,
        float groundAlbedo,
        float horizonSoftenRadians) {
}
