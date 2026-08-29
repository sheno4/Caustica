module guide_rmiss;

import world_minimal;

[shader("miss")]
void main(inout WorldPayload payload) {
    if ((payload.pathFlags & WORLD_PATH_SHADOW) != 0u) {
        float remaining = max(payload.previousBsdfPdf - payload.hitDistance, 0.0);
        payload.radiance *= exp(-max(payload.volumeAbsorption, float3(0.0)) * remaining);
        payload.hitDistance = -1.0;
        payload.pathFlags |= WORLD_PATH_MISS;
        return;
    }
    payload.radiance = float3(0.0);
    payload.hitDistance = -1.0;
    payload.pathFlags |= WORLD_PATH_MISS;
}
