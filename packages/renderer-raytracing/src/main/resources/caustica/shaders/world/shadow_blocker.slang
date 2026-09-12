module shadow_blocker;

import world_minimal;

[shader("anyhit")]
void main(inout ShadowPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
    // The producer certifies full coverage and zero transmission for the entire geometry revision.
    // Zero visibility is independent of the ordering of any nearer glass or volume boundaries.
    payload.transmission = float3(0.0);
    payload.hitDistance = RayTCurrent();
    AcceptHitAndEndSearch();
}
