package dev.comfyfluffy.caustica.minecraft;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Immutable Minecraft host facts sampled together at the client frame ingress. */
public record MinecraftCapturedFrame(Optional<Celestial> celestial, Optional<CelestialAtlas> atlas,
                                     Optional<LightDescriptor.Spot> helmet,
                                     RetainedLightSnapshot terrainLights) {
    public MinecraftCapturedFrame {
        celestial = Objects.requireNonNull(celestial, "celestial");
        atlas = Objects.requireNonNull(atlas, "atlas");
        helmet = Objects.requireNonNull(helmet, "helmet");
        terrainLights = Objects.requireNonNull(terrainLights, "terrainLights");
    }

    public record Celestial(float sunAngleRadians, float moonAngleRadians, float starAngleRadians,
                            float starBrightness, int moonPhaseIndex, int seaLevel, double cameraY,
                            double metersPerSceneUnit, MinecraftLightingCalibration lighting) {
        public Celestial { Objects.requireNonNull(lighting, "lighting"); }
    }

    public record CelestialAtlas(VulkanGpuTexture texture, int baseMipLevel, int mipLevels,
                                 Uv sunUv, Uv moonUv) {
        public CelestialAtlas {
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(sunUv, "sunUv");
            Objects.requireNonNull(moonUv, "moonUv");
        }
    }

    public record Uv(float u0, float v0, float u1, float v1) { }
}
