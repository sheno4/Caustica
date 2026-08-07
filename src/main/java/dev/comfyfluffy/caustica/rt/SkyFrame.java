package dev.comfyfluffy.caustica.rt;

/**
 * Semantic sky inputs for one frame, computed once from Minecraft's celestial state and consumed locally
 * by {@code RtComposite.skyPush()} to build the world push's sky fields (which feed the world push-constant
 * fill and, through it, NEE's sun/moon).
 *
 * <p>{@code dev.comfyfluffy.caustica.builtin.SkyLutPass} does <em>not</em> read this snapshot — it gathers
 * its own independent copy of the same Minecraft/look-package state each frame, deliberately, to exercise
 * the extension API as if the sky LUT bake were third-party code with no privileged access to the engine's
 * frame state. The two are allowed to drift by up to a frame's worth of partial-tick sampling order.
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
