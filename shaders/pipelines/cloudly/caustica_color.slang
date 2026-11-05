module caustica_color;

/** Inverse of the display bridge, preserving ordinary surface colours through composition. */
public float3 displayToExposedRadiance(float3 color) {
    color = max(color, float3(0));
    return color / max(float3(1) - color, float3(0.0001));
}

/** Fixed-exposure display bridge; the host supplies an already tone-mapped image. */
public float3 exposedRadianceToDisplay(float3 color) {
    color = max(color, float3(0));
    return color / (float3(1) + color);
}
