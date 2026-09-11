import environment_miss;
import minecraft_composition;
import world_minimal;

[shader("miss")]
void main(inout WorldPayload payload) {
    runEnvironmentMiss<MinecraftComposition>(payload);
}
