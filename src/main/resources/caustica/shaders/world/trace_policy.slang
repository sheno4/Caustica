module trace_policy;

public interface IRadianceTrace {
    public void trace(uint cullMask, float3 origin, float minimumDistance, float3 direction,
            float maximumDistance, bool showEnvironmentEmitter, float rayConeWidth, float rayConeSpread,
            uint pathPhaseHint);
};
