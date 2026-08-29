package dev.comfyfluffy.caustica.minecraft.material;

/** OpenPBR scalar defaults used by Minecraft's CPU material decoder. */
final class OpenPbrDefaults {
    static final float SPECULAR_IOR = 1.5f;
    static final float TRANSMISSIVE_SPECULAR_IOR = 1.333f;
    static final float TRANSMISSIVE_SPECULAR_ROUGHNESS = 0.0f;

    private OpenPbrDefaults() {
    }
}
