package dev.comfyfluffy.caustica.minecraft.rendering;

/** Celestial state captured from Minecraft's interpolated camera attribute probe. */
public record MinecraftCelestialFrame(float sunAngleRadians, float moonAngleRadians,
                                     float starAngleRadians, float starBrightness,
                                     int moonPhaseIndex, int seaLevel, double cameraY,
                                     double metersPerSceneUnit,
                                     MinecraftLightingCalibration lighting) { }
