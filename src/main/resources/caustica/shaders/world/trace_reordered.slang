module trace_reordered;

import trace_policy;
import trace_ser;

public struct ReorderedRadianceTrace : IRadianceTrace {
    public void trace(uint cullMask, float3 origin, float minimumDistance, float3 direction,
            float maximumDistance, bool showEnvironmentEmitter, float rayConeWidth, float rayConeSpread,
            uint pathPhaseHint) {
        traceRadianceReordered(cullMask, origin, minimumDistance, direction, maximumDistance,
                showEnvironmentEmitter, rayConeWidth, rayConeSpread, pathPhaseHint);
    }
};
