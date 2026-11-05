/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.sky;

/** The host look's photometric anchors, sampled with the celestial state. */
public record MinecraftLightingCalibration(float sunIlluminanceLux, float moonIlluminanceLux,
                                           float blockEmissionLuminanceCdM2,
                                           float nightAirglowLuminanceCdM2,
                                           float starLuminanceCdM2,
                                           float moonPhaseFixedFraction) { }
