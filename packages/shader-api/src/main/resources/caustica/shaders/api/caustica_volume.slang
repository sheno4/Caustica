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

/** One volume implementation selected by a geometry interior or a view spatial-medium binding. */
public interface IVolumeModel {
    /** Describe the homogeneous interior entered through this boundary. */
    public VolumeProperties evaluateVolume(VolumeInput input) {
        return {};
    }

    /** Describe local spatial transport; an implementation without spatial behavior is vacuum. */
    public SpatialMediumProperties evaluateSpatialMedium(SpatialMediumInput input) {
        return {};
    }

    /** Multiplicative direct-light response evaluated at a tracked boundary crossing. */
    public float3 evaluateBoundaryLighting(VolumeBoundaryLightingInput input) {
        return float3(1.0);
    }
};

/** Generated fan-out over volume implementations selected by geometry or a captured view. */
public interface IVolumeDispatch {
    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input);
    public SpatialMediumProperties evaluateSpatialMedium(uint implementation, SpatialMediumInput input);
    public float3 evaluateBoundaryLighting(uint implementation, VolumeBoundaryLightingInput input);
};
