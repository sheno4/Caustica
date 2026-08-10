module caustica_medium;

import caustica_types;

/** Reference one-world-unit Beer-Lambert mapping for an ordinary transmissive volume. */
public float3 referenceVolumeExtinction(float3 sourceColor, float transmissionWeight) {
    float3 unitTransmittance = clamp(clamp(sourceColor, 0.0, 1.0)
            * clamp(transmissionWeight, 0.0, 1.0), 1.0e-3, 1.0);
    return -log(unitTransmittance);
}

public MediumProperties referenceMedium(MediumInput input) {
    MediumProperties properties;
    properties.extinction = referenceVolumeExtinction(input.sourceColor, input.transmissionWeight);
    properties.guideTransmittance = input.sourceColor;
    properties.transmissionRayBias = 1.0e-4;
    properties.flags = 0u;
    return properties;
}
