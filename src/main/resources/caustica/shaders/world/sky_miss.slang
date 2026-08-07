// Engine-owned sky miss shader specialized with the selected composition's sky implementation.
//
// WorldShaderCompiler specializes this generic entry point with the active composition.
//
// The engine owns the entry point, descriptor bindings, payload ABI, and celestial-disc gate; the slot owns
// `evaluateEnvironment`, which returns scene-linear ACEScg radiance for a direction.
//
// It is compiled at runtime because the concrete composition is selected at activation. It no longer
// imports `sky` at all: celestial state left WorldPush for the selected sky slot's own buffer, so this
// wrapper has nothing to derive. The slot reads its own inputs through its own bindings; the engine's
// job here is the entry point, payload ABI, and the celestial-disc gate.
import world_common;
import bindings;
import caustica_types;
import caustica_api;
import frame;
// Slang forbids a generic-specialized entry point's type-parameter implementation (whatever TC.Sky
// resolves to) from declaring its own global shader parameters — the binding has to be reachable through
// an ordinary, non-generic import. This fixed, engine-generated module is that anchor: its content is
// whichever selected feature's own binding module(s) it imports (see FeatureBuilder.passResourceModule),
// generated fresh per composition, empty and trivially valid when none declare any. The engine never
// needs to know a specific pass's resource names for this to work.
import caustica_pass_resources;

[shader("miss")]
void main<TC : IComposition>(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];

    EnvironmentQuery query;
    query.frame = frameContext(worldPush);
    query.direction = normalize(WorldRayDirection());
    // Engine-owned double-count gate, set per-ray by raygen from the path state: a diffuse vertex that
    // already sampled the sun through NEE must not also see the disc. The surface slot classifies lobes;
    // it never sets this flag. See engineShowsCelestial in caustica_ray_engine_integrator.
    query.showCelestialBodies = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    TC.Sky skyModel;
    // Slot radiance is already scene-linear ACEScg, so the engine applies no additional gamut transform.
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
