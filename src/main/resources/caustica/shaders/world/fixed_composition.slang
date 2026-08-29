module fixed_composition;

import caustica_api;
import caustica_types;
import caustica_surface;
import caustica_coverage;
import caustica_volume;
import caustica_environment;
import caustica_error_surface;
import caustica_error_coverage;
import caustica_builtin_sky;

public struct FixedSurfaces : ISurfaceDispatch {
    public OpenPbrSurface evaluateSurface(uint implementation, SurfaceInput input) {
        ErrorSurface surface;
        return surface.evaluateSurface(input);
    }
};

public struct FixedCoverages : ICoverageDispatch {
    public float evaluateCoverage(uint implementation, CoverageInput input) {
        ErrorCoverage coverage;
        return coverage.evaluateCoverage(input);
    }
};

public struct VacuumVolume : IVolumeModel {
};

public struct FixedVolumes : IVolumeDispatch {
    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input) {
        VacuumVolume volume;
        return volume.evaluateVolume(input);
    }

    public float3 evaluateBoundaryLighting(uint implementation, VolumeBoundaryLightingInput input) {
        VacuumVolume volume;
        return volume.evaluateBoundaryLighting(input);
    }
};

public struct FixedEnvironments : IEnvironmentDispatch {
    public float3 evaluateEnvironment(uint implementation, EnvironmentQuery query) {
        BuiltinEnvironment environment;
        return environment.evaluateEnvironment(query);
    }
};

public struct FixedComposition : IComposition {
    public typealias Surfaces = FixedSurfaces;
    public typealias Coverages = FixedCoverages;
    public typealias Volumes = FixedVolumes;
    public typealias Environments = FixedEnvironments;
};
