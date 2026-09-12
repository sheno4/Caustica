module guide_rmiss;

import world_minimal;

[shader("miss")]
void main(inout WorldPayload payload) {
    payload.hitDistance = -1.0;
    payload.pathFlags |= WORLD_PATH_MISS;
}
