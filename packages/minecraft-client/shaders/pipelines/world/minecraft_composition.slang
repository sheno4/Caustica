module minecraft_composition;

import caustica_api;
import caustica_types;
import caustica_surface;
import caustica_coverage;
import caustica_volume;
import caustica_environment;
import caustica_error_surface;
import caustica_error_coverage;
import caustica_builtin_sky;
import caustica_minecraft_surface;
import caustica_minecraft_coverage;
import caustica_water_surface;
import caustica_portal_surface;
import caustica_minecraft_overworld_sky;

public struct MinecraftSurfaces : ISurfaceDispatch {
    public OpenPbrSurface evaluateSurface(uint implementation, SurfaceInput input) {
        switch (implementation) {
            case 1u: { MinecraftSurface value; return value.evaluateSurface(input); }
            case 2u: { WaterSurface value; return value.evaluateSurface(input); }
            case 3u: { PortalSurface value; return value.evaluateSurface(input); }
            default: { ErrorSurface value; return value.evaluateSurface(input); }
        }
    }
};

public struct MinecraftCoverages : ICoverageDispatch {
    public float evaluateCoverage(uint implementation, CoverageInput input) {
        switch (implementation) {
            case 1u:
            case 2u: { MinecraftCoverage value; return value.evaluateCoverage(input); }
            default: { ErrorCoverage value; return value.evaluateCoverage(input); }
        }
    }
};

public struct MinecraftVacuum : IVolumeModel {
};

public struct MinecraftVolumes : IVolumeDispatch {
    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input) {
        switch (implementation) {
            case 1u: { WaterVolume value; return value.evaluateVolume(input); }
            default: { MinecraftVacuum value; return value.evaluateVolume(input); }
        }
    }

    public float3 evaluateBoundaryLighting(uint implementation, VolumeBoundaryLightingInput input) {
        switch (implementation) {
            case 1u: { WaterVolume value; return value.evaluateBoundaryLighting(input); }
            default: { MinecraftVacuum value; return value.evaluateBoundaryLighting(input); }
        }
    }
};

public struct MinecraftEnvironments : IEnvironmentDispatch {
    public float3 evaluateEnvironment(uint implementation, EnvironmentQuery query) {
        switch (implementation) {
            case 1u: { MinecraftOverworldSky value; return value.evaluateEnvironment(query); }
            default: { BuiltinEnvironment value; return value.evaluateEnvironment(query); }
        }
    }
};

public struct MinecraftComposition : IComposition {
    public typealias Surfaces = MinecraftSurfaces;
    public typealias Coverages = MinecraftCoverages;
    public typealias Volumes = MinecraftVolumes;
    public typealias Environments = MinecraftEnvironments;
};
