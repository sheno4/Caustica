// Engine-owned sky miss shader specialized with the selected ray pack's appearance implementation.
//
// Deliberately named WITHOUT a `.rmiss.` stage infix: build.gradle's compileShaders compiles every
// `**/*.<stage>.slang` directly, and this entry point is generic over TPack, so a build-time compile
// would fail for want of a type argument. Following the existing "no stage infix = reached only through
// import" convention keeps it out of that task without needing a special-case exclusion.
//
// This is the first entry point where pack code actually executes inside the world pipeline. It is the
// engine wrapper described in docs/EXTENSION_API.md section 4.3: the engine owns the entry
// point, the descriptor bindings, the payload ABI, and the celestial-disc gate; the pack owns only
// `evaluateEnvironment`, which returns scene-linear ACEScg radiance for a direction.
//
// It is compiled at runtime (RayPackShaderCompiler) rather than at build time, because the concrete
// pack type is not known until an epoch is activated. It deliberately does NOT import `sky`'s LUT
// sampling for the dome — a pack that wants atmosphere computes it itself — but it DOES import `sky`
// for skyState/celestialDirection so the sun and moon directions it hands the pack are bit-identical to
// the ones the engine's own NEE uses in indirect.rgen. Deriving them separately here would let the
// visible sun drift away from the light that actually casts shadows.
//
// Known divergence while this path is enabled: indirect.rgen still derives celestialLight's illuminance
// from the transmittance LUT, so direct sun/moon LIGHTING remains atmospheric even when the pack paints
// a flat sky. Reconciling those is the reconstruction/lighting-service work, not this wrapper's job.
import world_common;
import bindings;
import sky;
import caustica_ray_pack_api;
import pack_frame;

[shader("miss")]
void main<TPack : IRayPack>(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    SkyState state = skyState(worldPush);

    PackEnvironmentQuery query;
    query.frame = packFrameContext(worldPush, state);
    query.direction = normalize(WorldRayDirection());
    // Engine-owned double-count gate, set per-ray by raygen from the path state: a diffuse vertex that
    // already sampled the sun through NEE must not also see the disc. The pack classifies its lobes;
    // it never sets this flag. See engineShowsCelestial in caustica_ray_engine_integrator.
    query.showCelestialBodies = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    TPack pack;
    // Pack radiance is already scene-linear ACEScg by contract, so unlike sky.rmiss there is no
    // bt709ToAcesCg conversion here — applying one would double-convert the pack's output.
    float3 radiance = max(pack.evaluateEnvironment(query), float3(0.0));

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
