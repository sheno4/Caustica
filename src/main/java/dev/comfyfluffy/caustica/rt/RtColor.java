package dev.comfyfluffy.caustica.rt;

/**
 * Host-side colour conversions matching {@code caustica_color.slang}. Values the engine hands the GPU as
 * colours are linear ACEScg; vanilla authors in 8-bit sRGB, so every boundary that reads Minecraft colour
 * data crosses both transforms here.
 */
public final class RtColor {
    private RtColor() {
    }

    /** sRGB electro-optical transfer function: encoded sRGB to linear BT.709, before {@link #linearBt709ToAcesCg}. */
    public static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** OCIO cg-config-v4.0.0 ACES 2.0: Linear Rec.709 (sRGB)/D65 to ACEScg/AP1/D60. */
    public static float[] linearBt709ToAcesCg(double r, double g, double b) {
        return new float[]{
                (float) (0.61309743 * r + 0.33952314 * g + 0.04737945 * b),
                (float) (0.07019372 * r + 0.91635388 * g + 0.01345240 * b),
                (float) (0.02061559 * r + 0.10956977 * g + 0.86981463 * b)};
    }
}
