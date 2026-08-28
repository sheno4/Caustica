module caustica_volume;

import caustica_types;

/**
 * Beer-Lambert mapping for a homogeneous volume. Source colour is interpreted as transmittance through
 * one metre; the result is extinction per scene unit.
 */
public float3 referenceVolumeExtinction(float3 transmittancePerMeter, float metersPerSceneUnit) {
    return -log(clamp(transmittancePerMeter, 1.0e-3, 1.0)) * metersPerSceneUnit;
}

/** Reference homogeneous volume derived from transmittance through one metre. */
public VolumeProperties referenceVolume(float3 transmittancePerMeter, float metersPerSceneUnit) {
    VolumeProperties properties;
    properties.extinction = referenceVolumeExtinction(transmittancePerMeter, metersPerSceneUnit);
    properties.guideTransmittance = clamp(transmittancePerMeter, 0.0, 1.0);
    properties.boundaryRayBias = 1.0e-4 / metersPerSceneUnit;
    properties.flags = 0u;
    return properties;
}

/** One volume implementation selected independently by a mesh geometry's interior slot. */
public interface IVolumeModel {
    /** Describe the homogeneous interior entered through this boundary. */
    public VolumeProperties evaluateVolume(VolumeInput input) {
        return referenceVolume(float3(1.0), input.metersPerSceneUnit);
    }

    /** Multiplicative direct-light response evaluated at a tracked boundary crossing. */
    public float3 evaluateVolumeLighting(VolumeLightingInput input) {
        return float3(1.0);
    }
};

/** Generated fan-out over registered volume implementations, selected per mesh geometry. */
public interface IVolumeDispatch {
    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input);
    public float3 evaluateVolumeLighting(uint implementation, VolumeLightingInput input);
};
