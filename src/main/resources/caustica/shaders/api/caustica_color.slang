// Colour-space primitives shared by the engine and every extension.
//
// The renderer works in scene-linear ACEScg (AP1/D60) end to end. Minecraft's atlases, vanilla sprite
// textures, and captured vertex colours are authored as sRGB/BT.709 (D65), so a colour crosses this
// OCIO-derived chromatic-adaptation + primary-conversion seam exactly once, before it enters any BRDF,
// throughput, medium, light, guide, or radiance calculation. A surface or sky slot returning radiance
// converts here rather than defining its own matrix, or the two sides drift.
module caustica_color;

public static const float3 ACESCG_LUMA = float3(0.27222872, 0.67408177, 0.05368952);
public static const float3 BT709_TO_ACESCG_R = float3(0.61309743, 0.33952314, 0.04737945);
public static const float3 BT709_TO_ACESCG_G = float3(0.07019372, 0.91635388, 0.01345240);
public static const float3 BT709_TO_ACESCG_B = float3(0.02061559, 0.10956977, 0.86981463);

public float3 bt709ToAcesCg(float3 color) {
    return float3(
            dot(color, BT709_TO_ACESCG_R),
            dot(color, BT709_TO_ACESCG_G),
            dot(color, BT709_TO_ACESCG_B));
}

/** sRGB electro-optical transfer function: encoded sRGB to linear BT.709, before bt709ToAcesCg. */
public float3 srgbToLinear(float3 color) {
    float3 lo = color / 12.92;
    float3 hi = pow((color + 0.055) / 1.055, float3(2.4));
    return lerp(lo, hi, step(float3(0.04045), color));
}
