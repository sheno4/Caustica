package dev.comfyfluffy.caustica.minecraft.rendering;

import java.util.Objects;

/** Celestial and atlas inputs required to record Minecraft sky work. */
public record MinecraftSkyFrame(MinecraftCelestialFrame celestial, CelestialAtlas atlas) {
    public MinecraftSkyFrame {
        Objects.requireNonNull(celestial, "celestial");
        Objects.requireNonNull(atlas, "atlas");
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
