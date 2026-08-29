module caustica_error_coverage;

import caustica_coverage;

/** Conservative visible failure output for an invalid or rejected coverage implementation. */
public struct ErrorCoverage : ICoverageModel {
    public override float evaluateCoverage(CoverageInput input) {
        return 1.0;
    }
};
