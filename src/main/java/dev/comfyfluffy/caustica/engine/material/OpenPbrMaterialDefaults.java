package dev.comfyfluffy.caustica.engine.material;

/** Numeric defaults for the renderer's supported OpenPBR material parameters. */
public final class OpenPbrMaterialDefaults {
    /** OpenPBR's default {@code specular_ior}, corresponding to normal-incidence reflectance 0.04. */
    public static final float DEFAULT_SPECULAR_IOR = 1.5f;
    /** Default IOR for an unclassified transmissive dielectric. */
    public static final float TRANSMISSIVE_SPECULAR_IOR = 1.52f;
    /** Reference IOR used by the renderer's current participating liquid volume. */
    public static final float REFERENCE_LIQUID_IOR = 1.333f;
    /** Default perceptual roughness for runtime entity textures. */
    public static final float ENTITY_SPECULAR_ROUGHNESS = 0.8f;
    /** Perceptual roughness of a volume dielectric when no texture authors it. */
    public static final float TRANSMISSIVE_SPECULAR_ROUGHNESS = 0.05f;

    private OpenPbrMaterialDefaults() {
    }
}
