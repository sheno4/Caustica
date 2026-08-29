module caustica_coverage;

import caustica_types;

/**
 * What coverage evaluation gets. The same uninterpreted words shading gets, and nothing else: an any-hit
 * that decides whether a hit exists needs its surface slot's binding data, not the engine's opinion about
 * textures. The host-side CoveragePolicy decides whether this function runs and how its alpha is tested.
 *
 * <p>Deliberately narrower than SurfaceInput. This runs in traversal, once per candidate hit rather than
 * once per accepted one, so it carries no position, no normal, and no footprint — a coverage test that
 * wants them is doing shading work in the wrong stage.
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
 * the geometry's Cutout policy compares it with alphaCutoff. Optical colour and transmission belong to
 * the surface result and never affect whether traversal accepts the candidate.
 */
public interface ICoverageModel {
    public float evaluateCoverage(CoverageInput input);
};

/** Generated fan-out selected by the same implementation byte as surface shading. */
public interface ICoverageDispatch {
    public float evaluateCoverage(uint implementation, CoverageInput input);
};
