module retained_trace_policy;

import world_minimal;

public interface IRetainedRadianceTrace {
    public void trace(inout WorldPayload payload, RayDesc ray, uint pathPhaseHint);
};
