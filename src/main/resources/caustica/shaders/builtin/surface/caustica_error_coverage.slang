module caustica_error_coverage;

import caustica_coverage;

/** Conservative visible failure output for an invalid or rejected coverage implementation. */
public struct ErrorCoverage : ICoverageModel {
    public override float4 evaluateCoverage(CoverageInput input) {
        return float4(1.0, 0.0, 1.0, 1.0);
    }
};
