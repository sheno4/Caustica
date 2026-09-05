module build_stable_planes;
import retained_lights;
import path_queue_types;
import surface_bsdf;
import caustica_volume;
import caustica_types;
import bindings;
import world_minimal;
import world_common;
import caustica_api;
import trace_transport;
import stable_planes;
import stable_plane_types;

struct StableBranch {
    PackedPathSegment path;
    float3x3 imageTransform;
    float3 motion;
    float length;
    uint identity;
    bool transmitted;
};

float3x3 branchReflection(float3 n) {
    return float3x3(1.0 - 2.0*n.x*n.x, -2.0*n.x*n.y, -2.0*n.x*n.z,
            -2.0*n.y*n.x, 1.0 - 2.0*n.y*n.y, -2.0*n.y*n.z,
            -2.0*n.z*n.x, -2.0*n.z*n.y, 1.0 - 2.0*n.z*n.z);
}

StableBranch branchAdvance(StableBranch branch, WorldPayload hit, TraceLobes lobes, bool transmission) {
    float3 incidentDirection = branch.path.direction;
    float3 direction = transmission ? lobes.transmittedDirection : lobes.reflectedDirection;
    branch.path = traceAdvance(branch.path, hit, direction,
            transmission ? lobes.transmission : lobes.reflection,
            transmission && lobes.volumeTransmission);
    float3x3 transform = branchReflection(hit.surface.shadingNormal);
    if (transmission) {
        // Rotate the refracted endpoint's guide frame back into the incident image frame.
        float3 from = normalize(direction);
        float3 to = incidentDirection;
        float3 v = cross(from, to);
        float c = dot(from, to);
        float3x3 skew = float3x3(0,-v.z,v.y,v.z,0,-v.x,-v.y,v.x,0);
        transform = float3x3(1,0,0,0,1,0,0,0,1) + skew + mul(skew, skew) / (1.0 + c);
    }
    float3 movement = hit.previousPosition - hit.position;
    branch.motion += mul(branch.imageTransform, movement - mul(transform, movement));
    branch.imageTransform = mul(branch.imageTransform, transform);
    branch.identity = (branch.identity << 2u) | (transmission ? 2u : 1u);
    branch.transmitted |= transmission;
    return branch;
}

public void runBuildStablePlanes<TComposition : IComposition>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    DevicePtr<PackedPathSegment> endpoints = (DevicePtr<PackedPathSegment>)worldBindings.pathQueueAddress;
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        endpoints[stablePlaneRecordIndex(pixel, extent, i)].flags = 0u;
    }
    StableBranch branches[STABLE_PLANE_COUNT];
    branches[0] = {};
    branches[0].path = traceCamera<TComposition>(pixel, extent, frame);
    branches[0].imageTransform = float3x3(1,0,0,0,1,0,0,0,1);
    branches[0].identity = 1u;
    float3 cameraDirection = branches[0].path.direction;
    uint count = 1u;
    float3 stableRadiance = float3(0.0);
    [unroll]
    for (uint plane = 0u; plane < STABLE_PLANE_COUNT; plane++) {
        if (plane >= count) break;
        StableBranch branch = branches[plane];
        for (uint step = 0u; step <= STABLE_PLANE_MAX_DELTA_SEGMENTS; step++) {
            WorldPayload hit = {};
            hit.seed = traceSeed(pixel, frame.frameIndex, plane);
            hit.pathFlags = WORLD_PATH_GUIDE;
            RayDesc ray = traceRay(branch.path);
            uint mask = branch.path.depth == 0u ? WORLD_RAY_MASK_PRIMARY : WORLD_RAY_MASK_SECONDARY;
            TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE, mask, 0u, 2u, 0u, ray, hit);
            bool sky = (hit.pathFlags & WORLD_PATH_MISS) != 0u;
            float distance = sky ? TRACE_MAX_DISTANCE : hit.hitDistance;
            float3 transmission = exp(-branch.path.absorption * distance);
            // Restart stores incoming throughput; fill applies the endpoint segment's attenuation once.
            PackedPathSegment restart = branch.path;
            branch.path.throughput *= transmission;
            branch.length += distance;
            stableRadiance += branch.path.throughput * (sky ? hit.radiance : hit.surface.emissionRadiance);
            TraceLobes lobes = {};
            if (!sky) lobes = traceLobes(branch.path, hit, false);
            bool reflectDelta = lobes.reflectionProbability > 0.0;
            bool transmitDelta = lobes.transmissionProbability > 0.0;
            bool continuous = lobes.continuousProbability > 0.0;
            uint deltaCount = uint(reflectDelta) + uint(transmitDelta);
            uint extra = deltaCount + uint(continuous) - (deltaCount > 0u || continuous ? 1u : 0u);
            bool split = !sky && deltaCount > 0u && count + extra <= STABLE_PLANE_COUNT
                    && branch.path.depth < STABLE_PLANE_MAX_DELTA_SEGMENTS && branch.path.depth <= frame.maxBounces;
            if (split) {
                if (continuous) {
                    if (reflectDelta) {
                        StableBranch queued = branchAdvance(branch, hit, lobes, false);
                        if (count == 1u) branches[1] = queued;
                        else branches[2] = queued;
                        count++;
                    }
                    if (transmitDelta) {
                        StableBranch queued = branchAdvance(branch, hit, lobes, true);
                        if (count == 1u) branches[1] = queued;
                        else branches[2] = queued;
                        count++;
                    }
                    restart.flags |= PATH_ENDPOINT_CONTINUOUS;
                } else {
                    if (reflectDelta && transmitDelta) {
                        StableBranch queued = branchAdvance(branch, hit, lobes, true);
                        if (count == 1u) branches[1] = queued;
                        else branches[2] = queued;
                        count++;
                    }
                    branch = branchAdvance(branch, hit, lobes, !reflectDelta);
                    continue;
                }
            }
            StablePlaneRecord record = {};
            record.branchId = branch.identity;
            record.flags = (sky ? STABLE_PLANE_FLAG_SKY : STABLE_PLANE_FLAG_VALID)
                    | (branch.transmitted ? STABLE_PLANE_FLAG_TRANSMISSION : 0u)
                    | (min(branch.path.depth, 255u) << 8u);
            record.currentVirtualPosition = frame.camOffset + cameraDirection * branch.length;
            record.previousVirtualPosition = record.currentVirtualPosition + branch.motion
                    + (sky ? float3(0.0) : mul(branch.imageTransform, hit.previousPosition - hit.position));
            record.throughputLuminance = traceLuminance(branch.path.throughput);
            record.branchWeight = record.throughputLuminance;
            record.normal = sky ? -cameraDirection : normalize(mul(branch.imageTransform, hit.surface.shadingNormal));
            record.roughness = sky ? 1.0 : sqrt(hit.surface.alphaRoughness);
            if (!sky) {
                float3 diffuse, specular;
                surfaceDenoiserEstimates(hit.surface, -branch.path.direction, diffuse, specular);
                record.diffuseBsdfEstimate = diffuse * branch.path.throughput;
                record.specularBsdfEstimate = specular * branch.path.throughput;
                // Unsplit delta lobes are sampled by fill and belong to the endpoint's specular signal.
                if (!split) record.specularBsdfEstimate += branch.path.throughput
                        * (lobes.reflection + lobes.transmission);
                restart.hitDistance = hit.hitDistance;
                if (branch.path.depth <= frame.maxBounces) endpoints[stablePlaneRecordIndex(pixel, extent, plane)] = restart;
            }
            stablePlaneStoreRecord(pixel, extent, plane, record);
            break;
        }
    }
    for (uint i = count; i < STABLE_PLANE_COUNT; i++) {
        uint address = stablePlaneRecordIndex(pixel, extent, i);
        stablePlaneRecords()[address].branchId = STABLE_PLANE_INVALID_BRANCH;
        stablePlaneRecords()[address].flags = 0u;
    }
    worldFloat4StorageImage(worldBindings.resources.nrdStableRadiance)[pixel] = float4(stableRadiance, 1.0);
    stablePlaneExport(pixel, extent, frame, false);
}

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runBuildStablePlanes<TComposition>();
}
