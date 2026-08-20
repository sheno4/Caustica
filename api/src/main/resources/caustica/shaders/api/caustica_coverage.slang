module caustica_coverage;

public static const uint COVERAGE_TEXTURE_PRESENT = 1u;
public static const uint COVERAGE_TEXTURE_LINEAR = 2u;
public static const uint COVERAGE_MODE_OPAQUE = 4u;

/** Geometry and base-texture facts available to coverage evaluation in traversal stages. */
public struct CoverageInput {
    /** Epoch-local slot in the provider texture array. Read only when COVERAGE_TEXTURE_PRESENT is set. */
    public uint baseTextureIndex;
    /** COVERAGE_* bits resolved by the engine from its traversal binding. */
    public uint flags;
    /** Uniform scene-linear ACEScg OpenPBR base_color factor. */
    public float3 baseColorFactor;
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
