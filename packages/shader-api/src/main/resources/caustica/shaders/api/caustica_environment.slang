module caustica_environment;

import caustica_types;

/** Distant radiance arriving from every direction the scene's geometry does not cover. */
public interface IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query);

    /** Incident RGB transmittance in [0, 1] for direct distant lighting at this position. */
    public float3 evaluateDistantTransmittance(EnvironmentLightQuery query) {
        return float3(1.0);
    }
};

/**
 * The generated fan-out over every registered environment implementation, selected per scene rather than
 * per composition. The engine names only this interface; the switch behind it is generated with the
 * composition root, so adding an implementation costs a case and no engine change.
 */
public interface IEnvironmentDispatch {
    public float3 evaluateEnvironment(uint implementation, EnvironmentQuery query);
    public float3 evaluateDistantTransmittance(uint implementation, EnvironmentLightQuery query);
};
