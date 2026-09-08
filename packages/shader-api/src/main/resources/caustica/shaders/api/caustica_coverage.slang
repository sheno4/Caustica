module caustica_coverage;

import caustica_types;

/**
 * Data for deciding whether traversal accepts a candidate hit. Implementation, binding and instance
 * words have the same meanings as in SurfaceInput. The geometry's CoveragePolicy decides whether this
 * function runs and how its alpha is tested.
 *
 * Evaluation runs once per candidate hit and exposes only the primitive and barycentric data needed to
 * reconstruct coverage attributes. Position, normal and texture-footprint evaluation belongs to shading.
 */
public struct CoverageInput {
    public ShaderRootData shaderRoot;
    public uint64_t implementationData;
    public uint64_t bindingData;
    public uint64_t instanceData;
    /** Geometry-local, so `bindingData` indexes with it directly. */
    public uint primitiveIndex;
    /** Weights for triangle vertices one and two, sufficient to interpolate alpha-tested attributes. */
    public float2 barycentrics;
};

/**
 * Narrow coverage-only implementation reachable from any-hit. The scalar result is coverage in [0, 1];
 * the geometry's coverage policy applies its cutoff or stochastic acceptance rule. Optical colour and
 * transmission belong to the surface result and never affect whether traversal accepts the candidate.
 */
public interface ICoverageModel {
    public float evaluateCoverage(CoverageInput input);
};

/** Generated fan-out selected by the same implementation index as surface shading. */
public interface ICoverageDispatch {
    public float evaluateCoverage(uint implementation, CoverageInput input);
};
