// Participating-medium state and the depth-two stack used by dielectric transport.
module medium;

import caustica_surface;
import caustica_types;

public struct Medium {
    public float ior;
    public float3 extinction;
    /** Registered material implementation which produced this medium. */
    public uint implementation;
    /** MEDIUM_* behavior retained while the path remains inside. */
    public uint flags;
};

public struct MediumBoundary {
    public Medium medium;
    public float3 guideTransmittance;
    public float transmissionRayBias;
};

public struct MediumStack {
    public Medium current;
    public Medium outer;
};

public Medium airMedium() {
    Medium medium;
    medium.ior = 1.0;
    medium.extinction = float3(0.0);
    medium.implementation = 0u;
    medium.flags = 0u;
    return medium;
}

/** Beer-Lambert transmittance for an unbounded path, evaluated per channel to avoid 0 * infinity. */
public float3 unboundedMediumTransmittance(float3 extinction) {
    return float3(extinction.x > 0.0 ? 0.0 : 1.0,
            extinction.y > 0.0 ? 0.0 : 1.0,
            extinction.z > 0.0 ? 0.0 : 1.0);
}

public MediumStack makeMediumStack(Medium start) {
    MediumStack stack;
    stack.current = start;
    stack.outer = airMedium();
    return stack;
}

public void mediumPush(inout MediumStack stack, Medium entered) {
    stack.outer = stack.current;
    stack.current = entered;
}

public void mediumPop(inout MediumStack stack) {
    stack.current = stack.outer;
    stack.outer = airMedium();
}

public MediumBoundary evaluateMedium<TSurfaces : ISurfaceDispatch>(TSurfaces surfaces,
        uint implementation, float ior, float3 sourceColor, float transmissionWeight) {
    MediumInput input;
    input.sourceColor = sourceColor;
    input.specularIor = ior;
    input.transmissionWeight = transmissionWeight;
    MediumProperties properties = surfaces.evaluateMedium(implementation, input);
    MediumBoundary boundary;
    boundary.medium.ior = ior;
    boundary.medium.extinction = properties.extinction;
    boundary.medium.implementation = implementation;
    boundary.medium.flags = properties.flags;
    boundary.guideTransmittance = properties.guideTransmittance;
    boundary.transmissionRayBias = properties.transmissionRayBias;
    return boundary;
}
