module retained_trace_reordered;

import retained_trace_policy;
import bindings;
import world_minimal;

public struct RetainedReorderedTrace : IRetainedRadianceTrace {
    public void trace(inout WorldPayload payload, RayDesc ray, uint pathPhaseHint) {
        HitObject hit = HitObject::TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE,
                0xffu, 0u, 2u, 0u, ray, payload);
        uint coherenceHint = (pathPhaseHint << 1u) | (hit.GetShaderTableIndex() & 1u);
        ReorderThread(hit, coherenceHint, 4u);
        HitObject::Invoke(worldAccelerationStructure(worldBindings), hit, payload);
    }
};
