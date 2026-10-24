package dev.comfyfluffy.caustica.minecraft.rendering;

import java.util.Objects;

/** Celestial, atlas, weather and terrain fade inputs required to record Minecraft sky work. */
public record MinecraftSkyFrame(MinecraftCelestialFrame celestial, CelestialAtlas atlas,
                                float rainStrength, float thunderStrength, TerrainFade terrainFade) {
    public MinecraftSkyFrame(MinecraftCelestialFrame celestial, CelestialAtlas atlas) {
        this(celestial, atlas, 0, 0);
    }

    public MinecraftSkyFrame(MinecraftCelestialFrame celestial, CelestialAtlas atlas,
                             float rainStrength, float thunderStrength) {
        this(celestial, atlas, rainStrength, thunderStrength, TerrainFade.NONE);
    }

    public MinecraftSkyFrame {
        Objects.requireNonNull(celestial, "celestial");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(terrainFade, "terrainFade");
    }

    /** Captured render-distance fade bounds in metres; a zero end disables terrain fading. */
    public record TerrainFade(double startMeters, double endMeters) {
        public static final TerrainFade NONE = new TerrainFade(0, 0);
    }

    public record CelestialAtlas(CelestialAtlasImage image, int baseMipLevel, int mipLevels,
                                 Uv sunUv, Uv moonUv) {
        public CelestialAtlas {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(sunUv, "sunUv");
            Objects.requireNonNull(moonUv, "moonUv");
            if (baseMipLevel < 0 || mipLevels < 1) throw new IllegalArgumentException("invalid atlas mip range");
        }
    }

    public record Uv(float u0, float v0, float u1, float v1) {
        public Uv {
            if (!(u0 >= 0 && v0 >= 0 && u1 <= 1 && v1 <= 1 && u0 < u1 && v0 < v1)) {
                throw new IllegalArgumentException("invalid atlas UV rectangle");
            }
        }
    }
}
