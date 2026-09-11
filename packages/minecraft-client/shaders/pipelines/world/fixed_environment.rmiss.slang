import fixed_composition;
import environment_miss;
import world_minimal;

[shader("miss")]
void main(inout WorldPayload payload) {
    runEnvironmentMiss<FixedComposition>(payload);
}
