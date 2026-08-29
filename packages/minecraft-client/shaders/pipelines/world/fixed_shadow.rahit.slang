import fixed_composition;
import shadow_any_hit_rahit;
import world_minimal;

[shader("anyhit")]
void main(inout WorldPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
    runShadowAnyHit<FixedComposition>(payload, attributes);
}
