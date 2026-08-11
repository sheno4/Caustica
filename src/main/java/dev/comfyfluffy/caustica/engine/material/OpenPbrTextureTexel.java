package dev.comfyfluffy.caustica.engine.material;

/** Reusable semantic output for one canonical OpenPBR texture texel. */
public final class OpenPbrTextureTexel {
    public float specularRoughness = 1.0f;
    public float baseMetalness;
    public float emissionWeight;
    public float subsurfaceWeight;
    public float tangentNormalX;
    public float tangentNormalY;
    public float normalHeight;
    public float metalBaseColorR = 1.0f;
    public float metalBaseColorG = 1.0f;
    public float metalBaseColorB = 1.0f;
    public float specularIor = OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;

    public void reset() {
        specularRoughness = 1.0f;
        baseMetalness = 0.0f;
        emissionWeight = 0.0f;
        subsurfaceWeight = 0.0f;
        tangentNormalX = 0.0f;
        tangentNormalY = 0.0f;
        normalHeight = 0.0f;
        metalBaseColorR = 1.0f;
        metalBaseColorG = 1.0f;
        metalBaseColorB = 1.0f;
        specularIor = OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
    }
}
