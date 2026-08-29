module world_minimal;

import bindings;
import caustica_types;
import caustica_coverage;

[[vk::push_constant]] public WorldBindingRoots worldBindings;

public struct WorldPayload {
    public float3 radiance;
    public float hitDistance;
};

public ShaderRootData worldShaderRoot() {
    ShaderRootData root = {};
    root.abiVersion = CAUSTICA_SHADER_ABI_VERSION;
    root.compositionData = worldBindings.compositionDataAddress;
    return root;
}

public float2 worldBarycentrics(BuiltInTriangleIntersectionAttributes attributes) {
    return attributes.barycentrics;
}

public SurfaceInput worldSurfaceInput(BuiltInTriangleIntersectionAttributes attributes) {
    SurfaceInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.primitiveIndex = PrimitiveIndex();
    input.barycentrics = worldBarycentrics(attributes);
    input.position = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    input.geometricNormal = -normalize(WorldRayDirection());
    input.previousGeometricNormal = input.geometricNormal;
    input.outgoingDirection = -normalize(WorldRayDirection());
    input.metersPerSceneUnit = 1.0;
    return input;
}

public CoverageInput worldCoverageInput(BuiltInTriangleIntersectionAttributes attributes) {
    CoverageInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.primitiveIndex = PrimitiveIndex();
    input.barycentrics = worldBarycentrics(attributes);
    return input;
}

public EnvironmentQuery worldEnvironmentQuery() {
    EnvironmentQuery query = {};
    query.shaderRoot = worldShaderRoot();
    query.direction = normalize(WorldRayDirection());
    query.showEnvironmentEmitters = true;
    return query;
}
