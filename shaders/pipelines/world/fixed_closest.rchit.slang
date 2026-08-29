import fixed_composition;
import closest_hit;
import world_minimal;

[shader("closesthit")]
void main(inout WorldPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
    runClosestHit<FixedComposition>(payload, attributes);
}
