module showcase_volume;

import caustica_volume;
import caustica_types;

namespace api_showcase {
public struct AbsorbingVolume : IVolumeModel {
    public override VolumeProperties evaluateVolume(VolumeInput input) {
        VolumeProperties volume;
        volume.absorptionCoefficient = volumeAbsorptionFromTransmittance(
                float3(0.75, 0.9, 0.98), 1.0, input.metersPerSceneUnit);
        return volume;
    }

    public override float3 evaluateBoundaryLighting(VolumeBoundaryLightingInput input) {
        return float3(1.0);
    }
};
}
