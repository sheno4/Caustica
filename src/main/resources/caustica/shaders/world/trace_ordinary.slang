module trace_ordinary;

import trace_policy;
import trace;

public struct OrdinaryRadianceTrace : IRadianceTrace {
    public void trace(uint cullMask, float3 origin, float minimumDistance, float3 direction,
            float maximumDistance, bool showEnvironmentEmitter, float rayConeWidth, float rayConeSpread,
            uint pathPhaseHint) {
        traceRadiance(cullMask, origin, minimumDistance, direction, maximumDistance,
                showEnvironmentEmitter, rayConeWidth, rayConeSpread);
    }
};
