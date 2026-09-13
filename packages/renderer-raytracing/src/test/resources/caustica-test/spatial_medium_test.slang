module spatial_medium_test;
import caustica_volume;
import caustica_types;

public struct SpatialTestVolume : IVolumeModel {
    public override SpatialMediumProperties evaluateSpatialMedium(SpatialMediumInput input) {
        SpatialMediumProperties properties;
        properties.extinction = float3(0.01 + abs(input.position.y) * 0.0001);
        properties.scatteringAlbedo = float3(0.9);
        properties.ambientSource = float3(float(input.implementationData & 255u) * 0.0001);
        properties.anisotropy = 0.4;
        return properties;
    }
};
public struct HomogeneousTestVolume : IVolumeModel {
    public override VolumeProperties evaluateVolume(VolumeInput input) {
        VolumeProperties properties;
        properties.absorptionCoefficient = float3(0.2);
        return properties;
    }
};
