package dev.comfyfluffy.caustica.minecraft.material;

/** Minecraft's finite fallback profiles for textures without authored LabPBR surface parameters. */
public enum MinecraftMaterialProfile {
    ROUGH_DIELECTRIC(0.9f, 0.0f),
    CONDUCTOR(0.3f, 1.0f),
    SMOOTH_DIELECTRIC(0.1f, 0.0f),
    POLISHED_DIELECTRIC(0.35f, 0.0f),
    MEDIUM_ROUGH_DIELECTRIC(0.7f, 0.0f);

    private final float specularRoughness;
    private final float baseMetalness;

    MinecraftMaterialProfile(float specularRoughness, float baseMetalness) {
        this.specularRoughness = specularRoughness;
        this.baseMetalness = baseMetalness;
    }

    public float specularRoughness() {
        return specularRoughness;
    }

    public float baseMetalness() {
        return baseMetalness;
    }
}
