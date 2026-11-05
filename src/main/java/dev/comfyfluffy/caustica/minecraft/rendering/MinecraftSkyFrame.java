package dev.comfyfluffy.caustica.minecraft.rendering;

/** Immutable celestial, weather and render-distance inputs for the native sky. */
public record MinecraftSkyFrame(MinecraftCelestialFrame celestial, float rainStrength,
                                float thunderStrength, TerrainFade terrainFade) {
    /** Captured Minecraft terrain fade in physical metres. */
    public record TerrainFade(double startMeters, double endMeters) { }
}
