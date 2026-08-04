module caustica_surface;

import caustica_types;

public interface ISurfaceModel {
    public float evaluateCoverage(SurfaceInput input);
    public SurfaceClosure createSurface(SurfaceInput input);
    public BsdfEvaluation evaluateBsdf(BsdfQuery query);
    public BsdfSample sampleBsdf(BsdfSampleQuery query);
    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface);
};
