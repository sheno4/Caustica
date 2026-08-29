module showcase_coverage;

import caustica_coverage;
import caustica_types;

namespace api_showcase {
public struct TextureCoverage : ICoverageModel {
    public float evaluateCoverage(CoverageInput input) {
        return frac(input.barycentrics.x * 8.0) > 0.25 ? 1.0 : 0.0;
    }
};
}
