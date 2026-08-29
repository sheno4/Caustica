module test_coverage;

import caustica_coverage;

public struct TestCoverage : ICoverageModel {
    public override float4 evaluateCoverage(CoverageInput input) {
        return float4(input.vertexColor.rgb, input.vertexColor.a);
    }
};
