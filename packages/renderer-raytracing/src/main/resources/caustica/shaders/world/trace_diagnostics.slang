module trace_diagnostics;
import world_minimal;
import world_common;
import trace_diagnostics_config;
import trace_diagnostics_types;

public bool shadowDiagnosticsActive() {
    return SHADOW_DIAGNOSTICS_ENABLED && worldBindings.shadowDiagnosticsAddress != 0u;
}

public struct ShadowDiagnosticTrace {
    public uint restarts;
    public uint maxQueryProceed;
    public uint totalProceed;
    public uint unchangedOrigin;
    public uint repeatedPrimitive;
    public uint2 previousAccepted;
    public bool hasPrevious;
    public uint2 anomalyAccepted;
    public uint anomalyRestarts;
    public uint anomalyProceed;
    public uint anomalyFlags;
};

void shadowDiagnosticAnomaly(inout ShadowDiagnosticTrace trace, uint flags, uint2 accepted) {
    if (trace.anomalyFlags != 0u) return;
    trace.anomalyFlags = flags;
    trace.anomalyAccepted = accepted;
    trace.anomalyRestarts = trace.restarts;
    trace.anomalyProceed = trace.totalProceed;
}

public void shadowDiagnosticQuery(inout ShadowDiagnosticTrace trace, uint proceedCount,
        bool accepted, uint2 identity) {
    if (!shadowDiagnosticsActive()) return;
    trace.maxQueryProceed = max(trace.maxQueryProceed, proceedCount);
    trace.totalProceed += proceedCount;
    if (!accepted) return;
    if (trace.hasPrevious && all(identity == trace.previousAccepted)) {
        trace.repeatedPrimitive++;
        shadowDiagnosticAnomaly(trace, 2u, identity);
    }
    trace.previousAccepted = identity;
    trace.hasPrevious = true;
}

public void shadowDiagnosticAdvance(inout ShadowDiagnosticTrace trace, float3 before, float3 after) {
    if (!shadowDiagnosticsActive()) return;
    trace.restarts++;
    if (all(before == after)) {
        trace.unchangedOrigin++;
        shadowDiagnosticAnomaly(trace, 1u, trace.previousAccepted);
    }
}

public void shadowDiagnosticReport(ShadowDiagnosticTrace trace) {
    if (!shadowDiagnosticsActive()) return;
    DevicePtr<ShadowDiagnostics> data = (DevicePtr<ShadowDiagnostics>)worldBindings.shadowDiagnosticsAddress;
    uint ignored;
    // Ordinary calls perform no shared memory access; only exceptional work reaches the atomics.
    if (trace.restarts > SHADOW_RESTART_THRESHOLD)
        InterlockedMax(data[0].maxShadowRestartsAbove32, trace.restarts, ignored);
    if (trace.maxQueryProceed > SHADOW_QUERY_PROCEED_THRESHOLD)
        InterlockedMax(data[0].maxQueryProceedAbove256, trace.maxQueryProceed, ignored);
    if (trace.totalProceed > SHADOW_TOTAL_PROCEED_THRESHOLD)
        InterlockedMax(data[0].maxShadowProceedAbove512, trace.totalProceed, ignored);
    if (trace.unchangedOrigin != 0u)
        InterlockedAdd(data[0].unchangedOriginEvents, trace.unchangedOrigin, ignored);
    if (trace.repeatedPrimitive != 0u)
        InterlockedAdd(data[0].repeatedAcceptedPrimitiveEvents, trace.repeatedPrimitive, ignored);
    if (trace.anomalyFlags != 0u) {
        InterlockedCompareExchange(data[0].firstAnomalyFlags, 0u, trace.anomalyFlags, ignored);
        if (ignored == 0u) {
            uint2 pixel = DispatchRaysIndex().xy;
            data[0].firstAnomalyPixelX = pixel.x;
            data[0].firstAnomalyPixelY = pixel.y;
            data[0].firstAnomalyGeometryRecord = trace.anomalyAccepted.x;
            data[0].firstAnomalyPrimitive = trace.anomalyAccepted.y;
            data[0].firstAnomalyRestarts = trace.anomalyRestarts;
            data[0].firstAnomalyProceedSum = trace.anomalyProceed;
        }
    }
}
