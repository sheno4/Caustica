/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.sky;

/** Immutable celestial, weather and render-distance inputs for the native sky. */
public record MinecraftSkyFrame(MinecraftCelestialFrame celestial, float rainStrength,
                                float thunderStrength, TerrainFade terrainFade) {
    /** Captured Minecraft terrain fade in physical metres. */
    public record TerrainFade(double startMeters, double endMeters) { }
}
