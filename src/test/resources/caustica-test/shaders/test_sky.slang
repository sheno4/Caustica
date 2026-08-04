module test_sky;

import caustica_types;
import caustica_sky;
import test_sky_helper;

public struct TestSky : ISkyModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        return testSkyRadiance(query.direction);
    }
};
