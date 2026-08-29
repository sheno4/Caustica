package dev.comfyfluffy.caustica.minecraft.material;

/** CPU light-extraction semantics for one resolved Minecraft material. */
public record MinecraftMaterialEmission(float luminanceCdM2, boolean textureMapped,
                                        MinecraftEmissionFootprint footprint) {
    public static final MinecraftMaterialEmission NONE = new MinecraftMaterialEmission(0.0f, false, null);

    public MinecraftMaterialEmission {
        if (!Float.isFinite(luminanceCdM2) || luminanceCdM2 < 0.0f) {
            throw new IllegalArgumentException("emission luminance must be finite and non-negative");
        }
        if ((luminanceCdM2 == 0.0f) != (footprint == null)) {
            throw new IllegalArgumentException("emitting materials require a footprint");
        }
    }
}
