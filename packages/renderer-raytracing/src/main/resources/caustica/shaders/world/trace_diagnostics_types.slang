module trace_diagnostics_types;

// Maxima remain zero unless a visibility call exceeds the named threshold.
public static const uint SHADOW_RESTART_THRESHOLD = 32u;
public static const uint SHADOW_QUERY_PROCEED_THRESHOLD = 256u;
public static const uint SHADOW_TOTAL_PROCEED_THRESHOLD = 512u;

/** GPU-written frame diagnostics; the host reads only after the graphics submission completes. */
public struct ShadowDiagnostics {
    public uint maxShadowRestartsAbove32;
    public uint maxQueryProceedAbove256;
    public uint maxShadowProceedAbove512;
    public uint unchangedOriginEvents;
    public uint repeatedAcceptedPrimitiveEvents;
    public uint firstAnomalyPixelX;
    public uint firstAnomalyPixelY;
    public uint firstAnomalyGeometryRecord;
    public uint firstAnomalyPrimitive;
    public uint firstAnomalyRestarts;
    public uint firstAnomalyProceedSum;
    // CAS from zero claims the sample: bit zero is unchanged origin, bit one is repeated primitive.
    public uint firstAnomalyFlags;
};
