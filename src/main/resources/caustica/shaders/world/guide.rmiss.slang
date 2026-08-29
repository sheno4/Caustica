module guide_rmiss;

import world_minimal;

[shader("miss")]
void main(inout WorldPayload payload) {
    payload.radiance = float3(0.0);
    payload.hitDistance = -1.0;
}
