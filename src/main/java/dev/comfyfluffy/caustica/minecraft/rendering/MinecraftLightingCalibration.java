package dev.comfyfluffy.caustica.minecraft.rendering;

/** The host look's photometric anchors, sampled with the celestial state. */
public record MinecraftLightingCalibration(float sunIlluminanceLux, float moonIlluminanceLux,
                                           float blockEmissionLuminanceCdM2,
                                           float nightAirglowLuminanceCdM2,
                                           float starLuminanceCdM2,
                                           float moonPhaseFixedFraction) { }
