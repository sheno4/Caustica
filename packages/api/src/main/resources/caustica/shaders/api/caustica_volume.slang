module caustica_volume;

import caustica_types;

/**
 * Beer-Lambert mapping for a homogeneous volume. The reference distance is positive and in metres; the
 * result is an absorption coefficient per scene unit.
 */
public float3 volumeAbsorptionFromTransmittance(float3 transmittance,
        float referenceDistanceMeters, float metersPerSceneUnit) {
    return -log(clamp(transmittance, 1.0e-6, 1.0))
            * metersPerSceneUnit / referenceDistanceMeters;
}

/** One volume implementation selected independently by a mesh geometry's interior slot. */
public interface IVolumeModel {
    /** Describe the homogeneous interior entered through this boundary. */
    public VolumeProperties evaluateVolume(VolumeInput input) {
        return {};
    }

    /** Multiplicative direct-light response evaluated at a tracked boundary crossing. */
    public float3 evaluateBoundaryLighting(VolumeBoundaryLightingInput input) {
        return float3(1.0);
    }
};

/** Generated fan-out over registered volume implementations, selected per mesh geometry. */
public interface IVolumeDispatch {
    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input);
    public float3 evaluateBoundaryLighting(uint implementation, VolumeBoundaryLightingInput input);
};
