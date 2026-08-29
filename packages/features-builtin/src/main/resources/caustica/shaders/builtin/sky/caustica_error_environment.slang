module caustica_error_environment;

import caustica_environment;
import caustica_types;

/** Visible fallback for a scene binding whose registered environment is no longer active. */
public struct ErrorEnvironment : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float horizon = 1.0 - abs(clamp(query.direction.y, -1.0, 1.0));
        return lerp(float3(0.35, 0.0, 0.35), float3(1.0, 0.0, 1.0), horizon);
    }
};
