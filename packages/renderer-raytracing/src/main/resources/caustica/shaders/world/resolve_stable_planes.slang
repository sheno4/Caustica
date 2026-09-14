module resolve_stable_planes;
import stable_planes;
import bindings;
import world_common;
import world_minimal;
import stable_plane_types;
import path_queue_types;
import retained_lights;

[shader("raygeneration")]
void main() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    PathSegmentStorage endpoints = pathSegmentStorage(worldBindings.pathQueueAddress,
            STABLE_PLANE_COUNT * extent.x * extent.y);
    NeeAtState lighting = neeAtState(worldBindings.neeAtStateAddress)[0];
    uint pixelIndex = pixel.y * extent.x + pixel.x;
    uint seed = neeAtHash(pixelIndex ^ lighting.frameIndex ^ 0xa511e9b3u) | 1u;
    uint2 feedback = uint2(0u);
    for (uint plane = 0u; plane < STABLE_PLANE_COUNT; plane++) {
        uint address = stablePlaneRecordIndex(pixel, extent, plane);
        if ((endpoints.loadFlags(address) & PATH_ENDPOINT_VALID) == 0u) continue;
        uint2 candidate = endpoints.loadFeedback(address);
        float weight = asfloat(candidate.y);
        if (weight <= 0.0) continue;
        float total = asfloat(feedback.y) + weight;
        if (retainedLightRandom(seed) * total <= weight) feedback.x = candidate.x;
        feedback.y = asuint(total);
    }
    feedback.y = asuint(min(asfloat(feedback.y), NEE_AT_MAX_FEEDBACK_WEIGHT));
    ((DevicePtr<uint2>)lighting.pixelFeedbackAddress)[pixelIndex] = feedback;
    stablePlaneExportSignals(pixel, extent, frame);
}
