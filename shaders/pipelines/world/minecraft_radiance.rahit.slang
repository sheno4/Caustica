import radiance_any_hit_rahit;
import minecraft_composition;
import world_minimal;

[shader("anyhit")]
void main(inout WorldPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
    runRadianceAnyHit<MinecraftComposition>(payload, attributes);
}
