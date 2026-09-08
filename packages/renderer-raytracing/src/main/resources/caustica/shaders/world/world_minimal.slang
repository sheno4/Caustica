module world_minimal;

import bindings;
import world_common;
import caustica_types;
import caustica_coverage;
import surface_bsdf;

[[vk::push_constant]] public WorldBindingRoots worldBindings;

// Hit shaders only resolve geometry/material data. Transport and branch exploration belong to raygen.
public struct WorldPayload {
    public SurfaceClosure surface;
    public float3 position;
    public float hitDistance;
    public float3 previousPosition;
    public uint lightIndex;
    public float3 geometricNormal;
    public uint pathFlags;
    public float3 previousNormal;
    public uint seed;
    public float3 radiance;
    public float previousBsdfPdf;
    public float3 volumeAbsorption;
    public float volumeIor;
};
public static const uint WORLD_PATH_VOLUME_ACTIVE = 2u;
public static const uint WORLD_PATH_MISS = 4u;
public static const uint WORLD_PATH_PRIMARY = 8u;
public static const uint WORLD_PATH_GUIDE = 16u;
public static const uint WORLD_PATH_SHADOW = 32u;
public static const uint WORLD_RAY_MASK_SECONDARY = 0x01u;
public static const uint WORLD_RAY_MASK_PRIMARY = 0x02u;

public float worldCoverageRandom(uint seed, uint instanceIndex, uint geometryIndex, uint primitiveIndex) {
    uint state = seed ^ (instanceIndex * 0x9e3779b9u)
            ^ (geometryIndex * 0x85ebca6bu) ^ (primitiveIndex * 0xc2b2ae35u);
    state ^= 2747636419u;
    state *= 2654435769u;
    state ^= state >> 16u;
    state *= 2654435769u;
    state ^= state >> 16u;
    return float(state >> 8u) * (1.0 / 16777216.0);
}

public ShaderRootData worldShaderRoot() {
    ShaderRootData root = {};
    root.abiVersion = CAUSTICA_SHADER_ABI_VERSION;
    root.compositionData = worldBindings.compositionDataAddress;
    return root;
}

// Slang's InstanceID intrinsic lowers to Vulkan's InstanceCustomIndexKHR value.
public uint worldInstanceCustomIndex() { return InstanceID(); }

public RetainedGeometryRecord worldGeometryRecord() {
    ConstPtr<RetainedGeometryRecord> records =
            (ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress;
    return records[worldInstanceCustomIndex() + GeometryIndex()];
}

public RetainedInstanceRecord worldInstanceRecord(RetainedGeometryRecord record) {
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    ConstPtr<RetainedInstanceRecord> instances =
            (ConstPtr<RetainedInstanceRecord>)frame[0].currentInstanceTableAddress;
    return instances[record.instanceIndex];
}

// The worker's instance table uses this exact unsigned wrapping hash and linear probing.
public uint worldInstanceHash(uint64_t identity, uint64_t placementOrdinal) {
    uint64_t value = identity + 0x9e3779b97f4a7c15ull * (placementOrdinal + 1ull);
    value = (value ^ (value >> 30u)) * 0xbf58476d1ce4e5b9ull;
    value = (value ^ (value >> 27u)) * 0x94d049bb133111ebull;
    return uint(value ^ (value >> 31u));
}

public struct WorldInstanceHistory {
    public RetainedInstanceRecord current;
    public RetainedInstanceRecord previous;
    public bool hasPrevious;
    public bool compatiblePositions;
};

public WorldInstanceHistory worldInstanceHistory(RetainedGeometryRecord geometry) {
    WorldInstanceHistory history = {};
    history.current = worldInstanceRecord(geometry);
    history.previous = history.current;
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    if (frame[0].previousInstanceTableAddress == 0ull) return history;
    ConstPtr<RetainedInstanceRecord> previous =
            (ConstPtr<RetainedInstanceRecord>)frame[0].previousInstanceTableAddress;
    uint mask = frame[0].previousInstanceTableMask;
    uint slot = worldInstanceHash(history.current.identity, history.current.placementOrdinal) & mask;
    // At most half of the table is populated, so an absent identity reaches an empty entry.
    for (;;) {
        RetainedInstanceRecord candidate = previous[slot];
        if (candidate.identity == 0ull) return history;
        if (candidate.identity == history.current.identity
                && candidate.placementOrdinal == history.current.placementOrdinal) {
            history.previous = candidate;
            history.hasPrevious = true;
            history.compatiblePositions = candidate.meshRevision == history.current.meshRevision
                    || (candidate.topologyToken != 0ull
                    && candidate.topologyToken == history.current.topologyToken);
            return history;
        }
        slot = (slot + 1u) & mask;
    }
}

public uint worldEmitterLightIndex(RetainedGeometryRecord record) {
    if (record.emitterIndexAddress == 0u) return 0xffffffffu;
    ConstPtr<uint> emitters = (ConstPtr<uint>)record.emitterIndexAddress;
    return emitters[record.emitterPrimitiveBase + PrimitiveIndex()];
}

public float3 worldTriangleNormal(AffineTransform transform, float3 edgeU, float3 edgeV) {
    // Transform edges before crossing to preserve winding under nonuniform scale, shear, and reflection.
    return normalize(cross(transformDirection(transform, edgeU), transformDirection(transform, edgeV)));
}

public bool worldHitEntering(AffineTransform transform) {
    float3 edgeU = HitTriangleVertexPosition(1u) - HitTriangleVertexPosition(0u);
    float3 edgeV = HitTriangleVertexPosition(2u) - HitTriangleVertexPosition(0u);
    float3 outward = worldTriangleNormal(transform, edgeU, edgeV);
    return dot(outward, WorldRayDirection()) < 0.0;
}

public uint3 worldTriangleIndices(RetainedGeometryRecord record) {
    ConstPtr<uint> indices = (ConstPtr<uint>)record.indexAddress;
    uint first = record.firstIndex + PrimitiveIndex() * 3u;
    return uint3(indices[first], indices[first + 1u], indices[first + 2u]);
}

public float3 worldPreviousVertex(WorldInstanceHistory history, uint vertexIndex) {
    // Incompatible topology has no vertex correspondence; retain rigid motion using current geometry.
    RetainedInstanceRecord source = history.compatiblePositions ? history.previous : history.current;
    uint64_t address = source.positionAddress
            + uint64_t(vertexIndex) * uint64_t(source.positionStride);
    ConstPtr<float> position = (ConstPtr<float>)address;
    return float3(position[0], position[1], position[2]);
}

public float3 worldPreviousHitPosition(BuiltInTriangleIntersectionAttributes attributes,
                                       RetainedGeometryRecord record, WorldInstanceHistory history) {
    uint3 indices = worldTriangleIndices(record);
    float3 p0 = worldPreviousVertex(history, indices.x);
    float3 p1 = worldPreviousVertex(history, indices.y);
    float3 p2 = worldPreviousVertex(history, indices.z);
    float2 barycentrics = attributes.barycentrics;
    float3 objectPosition = p0 * (1.0 - barycentrics.x - barycentrics.y)
            + p1 * barycentrics.x + p2 * barycentrics.y;
    return transformPosition(worldPreviousTransform(history), objectPosition);
}

public AffineTransform worldCurrentTransform(RetainedInstanceRecord record) {
    AffineTransform value = {};
    value.row0 = record.currentRow0;
    value.row1 = record.currentRow1;
    value.row2 = record.currentRow2;
    return value;
}

public AffineTransform worldPreviousTransform(WorldInstanceHistory history) {
    AffineTransform value = worldCurrentTransform(history.previous);
    if (history.hasPrevious) {
        ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
        value.row0.w += frame[0].previousOriginDelta.x;
        value.row1.w += frame[0].previousOriginDelta.y;
        value.row2.w += frame[0].previousOriginDelta.z;
    }
    return value;
}

public NormalTransform worldNormalTransform(AffineTransform affine) {
    float3 row0 = affine.row0.xyz;
    float3 row1 = affine.row1.xyz;
    float3 row2 = affine.row2.xyz;
    float inverseDeterminant = 1.0 / dot(row0, cross(row1, row2));
    NormalTransform value = {};
    value.row0 = cross(row1, row2) * inverseDeterminant;
    value.row1 = cross(row2, row0) * inverseDeterminant;
    value.row2 = cross(row0, row1) * inverseDeterminant;
    return value;
}

public SurfaceInput worldSurfaceInput(BuiltInTriangleIntersectionAttributes attributes,
                                      RetainedGeometryRecord record, WorldInstanceHistory history) {
    SurfaceInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = record.surfaceBinding;
    input.instanceData = history.current.instanceData;
    input.primitiveIndex = PrimitiveIndex();
    input.barycentrics = attributes.barycentrics;
    input.objectToScene = worldCurrentTransform(history.current);
    input.previousObjectToScene = worldPreviousTransform(history);
    input.normalToScene = worldNormalTransform(input.objectToScene);
    input.previousNormalToScene = worldNormalTransform(input.previousObjectToScene);
    input.position = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    float3 objectEdgeU = HitTriangleVertexPosition(1u) - HitTriangleVertexPosition(0u);
    float3 objectEdgeV = HitTriangleVertexPosition(2u) - HitTriangleVertexPosition(0u);
    uint3 triangleIndices = worldTriangleIndices(record);
    float3 previousObjectPosition0 = worldPreviousVertex(history, triangleIndices.x);
    float3 previousObjectEdgeU = worldPreviousVertex(history, triangleIndices.y)
            - previousObjectPosition0;
    float3 previousObjectEdgeV = worldPreviousVertex(history, triangleIndices.z)
            - previousObjectPosition0;
    float3 currentNormal = worldTriangleNormal(input.objectToScene, objectEdgeU, objectEdgeV);
    float3 previousNormal = worldTriangleNormal(input.previousObjectToScene,
            previousObjectEdgeU, previousObjectEdgeV);
    float orientation = dot(currentNormal, WorldRayDirection()) <= 0.0 ? 1.0 : -1.0;
    input.geometricNormal = currentNormal * orientation;
    input.previousGeometricNormal = previousNormal * orientation;
    input.outgoingDirection = -normalize(WorldRayDirection());
    input.metersPerSceneUnit = neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
    input.proceduralPosition = input.position + frame[0].proceduralDomainOffset;
    input.time = frame[0].time;
    input.previousTime = frame[0].previousTime;
    input.evaluationFlags = SURFACE_RECONSTRUCTION_ANCHOR
            | ((frame[0].flags & 16u) != 0u ? EVALUATION_ANIMATION_ENABLED : 0u);
    return input;
}

public CoverageInput worldCoverageInput(BuiltInTriangleIntersectionAttributes attributes) {
    RetainedGeometryRecord record = worldGeometryRecord();
    CoverageInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = record.surfaceBinding;
    input.instanceData = worldInstanceRecord(record).instanceData;
    input.primitiveIndex = PrimitiveIndex();
    input.barycentrics = attributes.barycentrics;
    return input;
}

public EnvironmentQuery worldEnvironmentQuery(bool showEmitters) {
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    EnvironmentQuery query = {};
    query.shaderRoot = worldShaderRoot();
    query.direction = normalize(WorldRayDirection());
    query.showEnvironmentEmitters = showEmitters
            || (neeAtState(worldBindings.neeAtStateAddress)[0].flags
            & NEE_AT_ENVIRONMENT_EMITTERS_SAMPLED) == 0u;
    query.bindingData = frame[0].environmentBinding;
    return query;
}

public uint worldEnvironmentImplementation() {
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    return frame[0].environmentImplementation;
}
