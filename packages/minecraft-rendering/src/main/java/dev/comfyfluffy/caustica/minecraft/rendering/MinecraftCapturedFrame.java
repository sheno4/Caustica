package dev.comfyfluffy.caustica.minecraft.rendering;

import java.util.Objects;
import java.util.Optional;

/** Immutable Minecraft rendering facts sampled together at frame ingress. */
public record MinecraftCapturedFrame(MinecraftLightFrame light, Optional<MinecraftSkyFrame> sky) {
    public MinecraftCapturedFrame {
        Objects.requireNonNull(light, "light");
        Objects.requireNonNull(sky, "sky");
    }
}
