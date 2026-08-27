module caustica_medium;

import caustica_types;

/** Reference one-world-unit Beer-Lambert mapping for an ordinary non-thin-walled transmissive volume. */
public float3 referenceVolumeExtinction(float3 sourceColor, float transmissionWeight) {
    float3 unitTransmittance = clamp(clamp(sourceColor, 0.0, 1.0)
            * clamp(transmissionWeight, 0.0, 1.0), 1.0e-3, 1.0);
    return -log(unitTransmittance);
}

/**
 * The reference interior for a transmissive surface, derived from the description the implementation
 * just produced. Nothing is read from an engine-uploaded material record: `material` is the same struct
 * `evaluateSurface` filled moments earlier.
 */
public MediumProperties referenceMedium(OpenPbrSurface material) {
    MediumProperties properties;
    properties.extinction = referenceVolumeExtinction(material.transmission_color, material.transmission_weight);
    properties.guideTransmittance = material.transmission_color;
    properties.transmissionRayBias = 1.0e-4;
    properties.flags = 0u;
    return properties;
}
