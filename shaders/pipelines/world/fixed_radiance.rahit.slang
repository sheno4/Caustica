import fixed_composition;
import radiance_any_hit_rahit;
import world_minimal;

[shader("anyhit")]
void main(inout WorldPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
    runRadianceAnyHit<FixedComposition>(payload, attributes);
}
