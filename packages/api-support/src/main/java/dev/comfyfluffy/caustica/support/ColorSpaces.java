package dev.comfyfluffy.caustica.support;

/** Color transforms used at API boundaries before values enter Caustica's ACEScg working space. */
public final class ColorSpaces {
    private ColorSpaces() {
    }

    /** sRGB electro-optical transfer function from encoded sRGB to linear BT.709. */
    public static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** OCIO cg-config-v4.0.0 ACES 2.0 transform from linear BT.709/D65 to ACEScg/AP1/D60. */
    public static float[] linearBt709ToAcesCg(double r, double g, double b) {
        return new float[]{
                (float) (0.61309743 * r + 0.33952314 * g + 0.04737945 * b),
                (float) (0.07019372 * r + 0.91635388 * g + 0.01345240 * b),
                (float) (0.02061559 * r + 0.10956977 * g + 0.86981463 * b)};
    }

    /** Converts encoded sRGB directly to scene-linear ACEScg/AP1/D60. */
    public static float[] srgbToAcesCg(double r, double g, double b) {
        return linearBt709ToAcesCg(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b));
    }
}
