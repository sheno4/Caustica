module shadow_rmiss;

import world_minimal;

[shader("miss")]
void main(inout ShadowPayload payload) {
    payload.hitDistance = -1.0;
}
