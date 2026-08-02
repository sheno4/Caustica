module default_pack;

import caustica_ray_pack_api;
import caustica_default_surface;
import caustica_default_environment;
import caustica_default_medium;

public struct DefaultRayPack : IRayPack {
    public float evaluateCoverage(PackSurfaceInput input) {
        return defaultCoverage(input);
    }

    public PackSurfaceClosure createSurface(PackSurfaceInput input) {
        return defaultCreateSurface(input);
    }

    public PackBsdfEvaluation evaluateBsdf(PackBsdfQuery query) {
        return defaultEvaluateBsdf(query);
    }

    public PackBsdfSample sampleBsdf(PackBsdfSampleQuery query) {
        return defaultSampleBsdf(query);
    }

    public float3 evaluateEnvironment(PackEnvironmentQuery query) {
        return defaultEvaluateEnvironment(query);
    }

    public uint getDirectionalLightCount(PackFrameContext frame) {
        return defaultDirectionalLightCount(frame);
    }

    public PackDirectionalLight getDirectionalLight(PackFrameContext frame, uint index) {
        return defaultDirectionalLight(frame, index);
    }

    public PackMedium createMedium(PackMediumInput input) {
        return defaultCreateMedium(input);
    }

    public PackPhaseEvaluation evaluatePhase(PackPhaseQuery query) {
        return defaultEvaluatePhase(query);
    }

    public PackPhaseSample samplePhase(PackPhaseSampleQuery query) {
        return defaultSamplePhase(query);
    }
};
