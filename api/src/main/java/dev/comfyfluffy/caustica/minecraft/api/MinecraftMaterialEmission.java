package dev.comfyfluffy.caustica.minecraft.api;

/** Minecraft light-extraction semantics paired with one resolved material definition. */
public record MinecraftMaterialEmission(float luminanceCdM2, boolean usesPrimitiveEmission,
                                        MinecraftEmissionFootprint footprint) {
    public static final MinecraftMaterialEmission NONE = new MinecraftMaterialEmission(0.0f, false, null);

    public MinecraftMaterialEmission {
        if (!Float.isFinite(luminanceCdM2) || luminanceCdM2 < 0.0f) {
            throw new IllegalArgumentException("emission luminance must be finite and non-negative");
        }
        if (luminanceCdM2 > 0.0f && footprint == null) {
            throw new IllegalArgumentException("emissive material needs an emission footprint");
        }
    }

    public boolean emissive() {
        return luminanceCdM2 > 0.0f;
    }
}
