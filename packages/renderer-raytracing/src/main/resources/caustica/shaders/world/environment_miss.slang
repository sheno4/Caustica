module environment_miss;
import world_minimal;
import caustica_api;
import caustica_environment;

public void runEnvironmentMiss<TComposition : IComposition>(inout WorldPayload payload) {
    payload.hitDistance = -1.0;
    payload.pathFlags |= WORLD_PATH_MISS;
}

[shader("miss")]
void main<TComposition : IComposition>(inout WorldPayload payload) {
    runEnvironmentMiss<TComposition>(payload);
}
