// Engine-owned sky miss shader specialized with the selected composition's sky implementation.
//
// Deliberately named WITHOUT a `.rmiss.` stage infix: build.gradle's compileShaders compiles every
// `**/*.<stage>.slang` directly, and this entry point is generic over a composition, so a build-time compile
// would fail for want of a type argument. Following the existing "no stage infix = reached only through
// import" convention keeps it out of that task without needing a special-case exclusion.
//
// The engine owns the entry point, descriptor bindings, payload ABI, and celestial-disc gate; the slot owns
// `evaluateEnvironment`, which returns scene-linear ACEScg radiance for a direction.
//
// It is compiled at runtime because the concrete composition is selected at activation. It deliberately
// does not import `sky`'s LUT sampling for the dome, but it does import `sky` for skyState so the sun and
// moon directions handed to the slot are bit-identical to
// the ones the engine's own NEE uses in indirect.rgen. Deriving them separately here would let the
// visible sun drift away from the light that actually casts shadows.
//
// Known divergence while this path is enabled: indirect.rgen still derives celestialLight's illuminance
// from the transmittance LUT, so direct sun/moon lighting remains atmospheric even when a slot paints
// a flat sky. Reconciling those is the reconstruction/lighting-service work, not this wrapper's job.
import world_common;
import bindings;
import sky;
import caustica_types;
import caustica_api;
import frame;

[shader("miss")]
void main<TC : IComposition>(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    SkyState state = skyState(worldPush);

    EnvironmentQuery query;
    query.frame = frameContext(worldPush, state);
    query.direction = normalize(WorldRayDirection());
    // Engine-owned double-count gate, set per-ray by raygen from the path state: a diffuse vertex that
    // already sampled the sun through NEE must not also see the disc. The surface slot classifies lobes;
    // it never sets this flag. See engineShowsCelestial in caustica_ray_engine_integrator.
    query.showCelestialBodies = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    TC.Sky skyModel;
    // Slot radiance is already scene-linear ACEScg, so unlike sky.rmiss it is not converted again.
    float3 radiance = max(skyModel.evaluateEnvironment(query), float3(0.0));

    packSky(payload, radiance);
    payload.hitT = -1.0;
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    payload.f0 = half3(0.0h, 0.0h, 0.0h);
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.iorTransmission = packHalf2(float2(1.0, 0.0));
    payload.rayCone = 0u;
}
