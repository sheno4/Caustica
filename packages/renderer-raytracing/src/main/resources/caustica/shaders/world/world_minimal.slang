module world_minimal;

import bindings;
import world_common;
import caustica_types;
import caustica_coverage;

[[vk::push_constant]] public WorldBindingRoots worldBindings;

public struct WorldPayload {
    public float3 radiance;
    public float hitDistance;
    public float3 feedbackThroughput;
    public float3 volumeAbsorption;
    public float volumeIor;
    public float3 previousPosition;
    public float previousBsdfPdf;
    public uint seed;
    public uint previousNeeProposalMode;
    public uint previousNeeCandidateCount;
    public uint currentNeeProposalMode;
    public uint currentNeeCandidateCount;
    // Continuation, current-medium, miss, primary-ray, and guide-only state.
    public uint pathFlags;
    public float3 hitPosition;
    public float roughness;
    public float3 previousHitPosition;
    public uint guideNormal;
    public uint previousGuideNormal;
    public uint guideAlbedo;
    public uint guideSpecularAlbedo;
    public uint queueRecordIndex;
    public float3 guideContinuationOrigin;
    public float3 guideContinuationDirection;
    public float3 guideContinuationAbsorption;
    public float guideContinuationIor;
    public uint guideContinuationVolumeActive;
    public uint guideContinuationEvent;
};

public static const uint WORLD_PATH_REQUEST_CONTINUATION = 1u;
public static const uint WORLD_PATH_VOLUME_ACTIVE = 2u;
public static const uint WORLD_PATH_MISS = 4u;
public static const uint WORLD_PATH_PRIMARY = 8u;
public static const uint WORLD_PATH_GUIDE = 16u;
public static const uint WORLD_PATH_SHADOW = 32u;
public static const uint WORLD_PATH_EMISSIVE = 64u;
public static const uint WORLD_PATH_GUIDE_CONTINUATION = 128u;
public static const uint WORLD_GUIDE_EVENT_NONE = 0u;
public static const uint WORLD_GUIDE_EVENT_REFLECTION = 1u;
public static const uint WORLD_GUIDE_EVENT_TRANSMISSION = 2u;
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
    return (float(state) + 0.5) * (1.0 / 4294967296.0);
}

public ShaderRootData worldShaderRoot() {
    ShaderRootData root = {};
    root.abiVersion = CAUSTICA_SHADER_ABI_VERSION;
    root.compositionData = worldBindings.compositionDataAddress;
    return root;
}

public float2 worldBarycentrics(BuiltInTriangleIntersectionAttributes attributes) {
    return attributes.barycentrics;
}

// Slang's InstanceID intrinsic lowers to Vulkan's InstanceCustomIndexKHR value.
public uint worldInstanceCustomIndex() { return InstanceID(); }

public RetainedGeometryRecord worldGeometryRecord() {
    ConstPtr<RetainedGeometryRecord> records =
            (ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress;
    return records[worldInstanceCustomIndex() + GeometryIndex()];
}

public uint worldEmitterLightIndex(RetainedGeometryRecord record) {
    if (record.emitterIndexAddress == 0u) return 0xffffffffu;
    ConstPtr<uint> emitters = (ConstPtr<uint>)record.emitterIndexAddress;
    return emitters[record.emitterPrimitiveBase + PrimitiveIndex()];
}

public bool worldHitEntering() {
    float3 edgeU = HitTriangleVertexPosition(1u) - HitTriangleVertexPosition(0u);
    float3 edgeV = HitTriangleVertexPosition(2u) - HitTriangleVertexPosition(0u);
    float3 outward = transformDirection(worldCurrentTransform(worldGeometryRecord()), cross(edgeU, edgeV));
    return dot(outward, WorldRayDirection()) < 0.0;
}

public float3 worldPreviousHitPosition(BuiltInTriangleIntersectionAttributes attributes,
                                       RetainedGeometryRecord record) {
    float2 barycentrics = attributes.barycentrics;
    float3 objectPosition = HitTriangleVertexPosition(0u) * (1.0 - barycentrics.x - barycentrics.y)
            + HitTriangleVertexPosition(1u) * barycentrics.x
            + HitTriangleVertexPosition(2u) * barycentrics.y;
    return transformPosition(worldPreviousTransform(record), objectPosition);
}

public AffineTransform worldCurrentTransform(RetainedGeometryRecord record) {
    AffineTransform value = {};
    value.row0 = record.currentRow0;
    value.row1 = record.currentRow1;
    value.row2 = record.currentRow2;
    return value;
}

public AffineTransform worldPreviousTransform(RetainedGeometryRecord record) {
    AffineTransform value = {};
    value.row0 = record.previousRow0;
    value.row1 = record.previousRow1;
    value.row2 = record.previousRow2;
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

public SurfaceInput worldSurfaceInput(BuiltInTriangleIntersectionAttributes attributes) {
    RetainedGeometryRecord record = worldGeometryRecord();
    SurfaceInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = record.surfaceBinding;
    input.instanceData = record.instanceData;
    input.primitiveIndex = PrimitiveIndex();
    input.barycentrics = worldBarycentrics(attributes);
    input.objectToScene = worldCurrentTransform(record);
    input.previousObjectToScene = worldPreviousTransform(record);
    input.normalToScene = worldNormalTransform(input.objectToScene);
    input.previousNormalToScene = worldNormalTransform(input.previousObjectToScene);
    input.position = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    float3 objectEdgeU = HitTriangleVertexPosition(1u) - HitTriangleVertexPosition(0u);
    float3 objectEdgeV = HitTriangleVertexPosition(2u) - HitTriangleVertexPosition(0u);
    float3 currentEdgeU = transformDirection(input.objectToScene, objectEdgeU);
    float3 currentEdgeV = transformDirection(input.objectToScene, objectEdgeV);
    float3 previousEdgeU = transformDirection(input.previousObjectToScene, objectEdgeU);
    float3 previousEdgeV = transformDirection(input.previousObjectToScene, objectEdgeV);
    float3 currentNormal = normalize(cross(currentEdgeU, currentEdgeV));
    float3 previousNormal = normalize(cross(previousEdgeU, previousEdgeV));
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
    input.instanceData = record.instanceData;
    input.primitiveIndex = PrimitiveIndex();
    input.barycentrics = worldBarycentrics(attributes);
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
