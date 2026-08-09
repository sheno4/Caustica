// Radiance-ray any-hit: alpha masking only. Transmissive geometry uses closest-hit-only records on this
// ray type, so nothing here touches the payload — accepted hits fall through to the closest-hit shader.
//
// Guide probes trace the radiance records with a cheaper miss, but they must not alpha-test
// stochastically: a guide answers "which surface does this reflection/refraction point at", and a
// coin-flip on that is a flickering control signal rather than a sample that averages out. Suppressing
// it lets a translucent entity reach closest-hit as a dielectric, which is exactly the crossing the
// guide loop already knows how to walk through — the entity behaves like glass.
import world_common;
import bindings;
import any_hit_common;

[shader("anyhit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float3 bary = barycentrics(attr.barycentrics);
    uint pid = PrimitiveIndex();

    if ((InstanceID() & (ENTITY_BIT | PARTICLE_BIT)) != 0u) {
        EntityHit hit = decodeEntityHit(bary, pid);
        bool allowStochastic = (InstanceID() & ENTITY_BIT) != 0u
                && (payload.flags & PAYLOAD_GUIDE_RAY) == 0u;
        if (entityAlphaFails(hit, pid, allowStochastic)) {
            IgnoreHit();
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    if (terrainCutoutFails(sec, bary, pid + sec.triBase[GeometryIndex()])) {
        IgnoreHit();
    }
}
