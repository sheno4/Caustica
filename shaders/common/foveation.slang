public static const float INSET_BLEND_WIDTH = 0.12;

// Inset and eye projections originate at the same eye. A point at far depth
// therefore maps to the same inset UV as any point on the eye pixel's ray.
public float2 projectInsetUv(float2 eyeUv, float4x4 eyeToInset) {
    float4 clip = mul(eyeToInset, float4(eyeUv * 2.0 - 1.0, 0.0, 1.0));
    return clip.w > 1.0e-6 ? 0.5 * (clip.xy / clip.w + 1.0) : float2(-2.0);
}

public float insetEdgeDistance(float2 insetUv) {
    float2 edges = min(insetUv, 1.0 - insetUv);
    return min(edges.x, edges.y);
}
