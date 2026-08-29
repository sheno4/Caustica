package dev.comfyfluffy.caustica.minecraft.material;

/**
 * Reusable semantic output for one canonical OpenPBR texture texel. Color fields are linear BT.709;
 * scalar fields are physical OpenPBR values except the tangent normal, whose x/y components are signed.
 */
public final class OpenPbrTextureTexel {
    public float specularRoughness = 1.0f;
    public float baseMetalness;
    public float emissionWeight;
    /** Linear BT.709 emissive texture multiplier. */
    public float emissionColorR = 1.0f;
    public float emissionColorG = 1.0f;
    public float emissionColorB = 1.0f;
    public float subsurfaceWeight;
    public float tangentNormalX;
    public float tangentNormalY;
    public float normalHeight;
    public float metalBaseColorR = 1.0f;
    public float metalBaseColorG = 1.0f;
    public float metalBaseColorB = 1.0f;
    public float specularIor = OpenPbrDefaults.SPECULAR_IOR;

    public void reset() {
        specularRoughness = 1.0f;
        baseMetalness = 0.0f;
        emissionWeight = 0.0f;
        emissionColorR = 1.0f;
        emissionColorG = 1.0f;
        emissionColorB = 1.0f;
        subsurfaceWeight = 0.0f;
        tangentNormalX = 0.0f;
        tangentNormalY = 0.0f;
        normalHeight = 0.0f;
        metalBaseColorR = 1.0f;
        metalBaseColorG = 1.0f;
        metalBaseColorB = 1.0f;
        specularIor = OpenPbrDefaults.SPECULAR_IOR;
    }
}
