public struct PresentPush {
    public uint outputImageIndex;
    public uint sourceImageIndex;
    public float uiNits;
};

// Shared transfer functions and gamut transforms for host-frame presentation.
public static const float PQ_M1 = 0.1593017578125;
public static const float PQ_M2 = 78.84375;
public static const float PQ_C1 = 0.8359375;
public static const float PQ_C2 = 18.8515625;
public static const float PQ_C3 = 18.6875;

public float pqEncode(float nits) {
    float y = pow(max(nits, 0.0) / 10000.0, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}

public float pqDecode(float e) {
    float ep = pow(max(e, 0.0), 1.0 / PQ_M2);
    float num = max(ep - PQ_C1, 0.0);
    float den = max(PQ_C2 - PQ_C3 * ep, 1.0e-6);
    return 10000.0 * pow(num / den, 1.0 / PQ_M1);
}

public float srgbChannelToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

public float3 srgbToLinear(float3 c) {
    return float3(srgbChannelToLinear(c.x), srgbChannelToLinear(c.y), srgbChannelToLinear(c.z));
}

public float linearChannelToSrgb(float c) {
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

public float3 linearToSrgb(float3 c) {
    return float3(linearChannelToSrgb(c.x), linearChannelToSrgb(c.y), linearChannelToSrgb(c.z));
}

// Row-major mathematical notation; the build targets column-major storage, while mul(M, v) preserves
// the intended transforms.
public static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953
);

public static const float3x3 BT2020_TO_BT709 = float3x3(
     1.6604910, -0.5876411, -0.0728499,
    -0.1245505,  1.1328999, -0.0083494,
    -0.0181508, -0.1005789,  1.1187297
);
