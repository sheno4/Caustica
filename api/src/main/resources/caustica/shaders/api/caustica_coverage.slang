module caustica_coverage;

import caustica_types;

public static const uint COVERAGE_MODE_OPAQUE = 1u;

/**
 * What coverage evaluation gets. The same uninterpreted words shading gets, and nothing else: an any-hit
 * that decides whether a hit exists needs its own data, not the engine's opinion about textures.
 *
 * <p>Deliberately narrower than SurfaceInput. This runs in traversal, once per candidate hit rather than
 * once per accepted one, so it carries no position, no normal, and no footprint — a coverage test that
 * wants them is doing shading work in the wrong stage.
 */
public struct CoverageInput {
    public ShaderRootData shaderRoot;
    public uint64_t surfaceData;
    public uint64_t geometryData;
    public uint64_t instanceData;
    /** Geometry-local, so `geometryData` indexes with it directly. */
    public uint primitiveIndex;
    /** Weights for triangle vertices one and two, sufficient to interpolate alpha-tested attributes. */
    public float2 barycentrics;
    /** COVERAGE_* bits resolved by the engine from its traversal binding. */
    public uint flags;
};

/**
 * Narrow coverage-only implementation reachable from any-hit. The result is the resolved transmission
 * tint in scene-linear ACEScg and coverage in alpha.
 */
public interface ICoverageModel {
    public float4 evaluateCoverage(CoverageInput input);
};

/** Generated fan-out selected by the same implementation byte as surface shading. */
public interface ICoverageDispatch {
    public float4 evaluateCoverage(uint implementation, CoverageInput input);
};
