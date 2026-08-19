package dev.comfyfluffy.caustica.api.provider;

/** Numeric defaults for the renderer's supported OpenPBR material parameters. */
public final class OpenPbrMaterialDefaults {
    /** OpenPBR's default {@code specular_ior}, corresponding to normal-incidence reflectance 0.04. */
    public static final float DEFAULT_SPECULAR_IOR = 1.5f;
    /** Default IOR for an unclassified transmissive dielectric. */
    public static final float TRANSMISSIVE_SPECULAR_IOR = 1.52f;
    /** Default perceptual roughness for runtime-resolved standalone textures. */
    public static final float RUNTIME_TEXTURE_SPECULAR_ROUGHNESS = 0.8f;
    /** Perceptual roughness of a volume dielectric when no texture authors it. */
    public static final float TRANSMISSIVE_SPECULAR_ROUGHNESS = 0.05f;

    private OpenPbrMaterialDefaults() {
    }
}
