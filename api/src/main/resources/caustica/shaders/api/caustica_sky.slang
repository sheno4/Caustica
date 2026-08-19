module caustica_sky;

import caustica_types;

public interface ISkyModel {
    public float3 evaluateEnvironment(EnvironmentQuery query);
};
