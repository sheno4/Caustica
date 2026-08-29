import closest_hit;
import minecraft_composition;
import world_minimal;

[shader("closesthit")]
void main(inout WorldPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
    runClosestHit<MinecraftComposition>(payload, attributes);
}
