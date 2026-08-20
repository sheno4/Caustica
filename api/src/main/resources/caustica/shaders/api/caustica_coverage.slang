module caustica_coverage;

/** Geometry and material identity available to coverage evaluation in traversal stages. */
public struct CoverageInput {
    /** The binding index stored by the hit primitive for this material epoch. */
    public uint materialId;
    public float2 textureCoordinate;
    /** Primitive tint in scene-linear ACEScg. */
    public float3 tint;
    /** Interpolated vertex colour in scene-linear ACEScg with unitless alpha. */
    public float4 vertexColor;
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
