module test_coverage_broken;

import caustica_coverage;

[[vk::binding(0, 3)]] Sampler2D brokenCoverageTexture;

public struct BrokenCoverage : ICoverageModel {
    public override float4 evaluateCoverage(CoverageInput input) {
        return brokenCoverageTexture.SampleLevel(input.textureCoordinate, 0.0);
    }
};
