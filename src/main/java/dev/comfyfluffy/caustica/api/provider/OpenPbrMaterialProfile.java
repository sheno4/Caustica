package dev.comfyfluffy.caustica.api.provider;

/** Host-neutral scalar axes selecting one precompiled renderer material profile. */
public record OpenPbrMaterialProfile(float specularRoughness, float baseMetalness) {
    public static final OpenPbrMaterialProfile ROUGH_DIELECTRIC = new OpenPbrMaterialProfile(0.9f, 0.0f);
    public static final OpenPbrMaterialProfile CONDUCTOR = new OpenPbrMaterialProfile(0.3f, 1.0f);
    public static final OpenPbrMaterialProfile SMOOTH_DIELECTRIC = new OpenPbrMaterialProfile(0.1f, 0.0f);
    public static final OpenPbrMaterialProfile POLISHED_DIELECTRIC = new OpenPbrMaterialProfile(0.35f, 0.0f);
    public static final OpenPbrMaterialProfile MEDIUM_ROUGH_DIELECTRIC = new OpenPbrMaterialProfile(0.7f, 0.0f);

    public OpenPbrMaterialProfile {
        unit("specularRoughness", specularRoughness);
        unit("baseMetalness", baseMetalness);
    }

    private static void unit(String name, float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }
}
