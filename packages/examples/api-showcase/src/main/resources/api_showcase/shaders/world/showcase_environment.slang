module showcase_environment;

import caustica_environment;
import caustica_types;

namespace api_showcase {
public struct GradientEnvironment : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float horizon = saturate(query.direction.y * 0.5 + 0.5);
        return lerp(float3(0.02, 0.03, 0.05), float3(0.2, 0.45, 0.9), horizon);
    }
};
}
