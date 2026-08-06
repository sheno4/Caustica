package dev.comfyfluffy.caustica.rt;

/**
 * Semantic sky inputs for one frame, computed once from Minecraft's celestial state and read by both the
 * world push-constant fill and {@code SkyLutPass} — kept as a single snapshot ({@link SkyFrameState}) so
 * the two can't drift within a frame.
 */
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
