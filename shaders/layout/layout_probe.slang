// Build-time reflection probe only. This file is not packaged as a runtime shader.
// Exposing WorldPush as a structured-buffer element makes Slang's JSON reflection include the
// complete Std430DataLayout: field types, byte offsets, total size, matrix mode, and array counts.
import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_sky_bindings;
import caustica_minecraft_sky_lut_types;
import caustica_minecraft_material;

struct MinecraftImplementationLayoutProbe { MinecraftImplementationData values[2]; };
struct MinecraftEnvironmentBindingLayoutProbe { MinecraftEnvironmentBinding values[2]; };

[[vk::binding(4, 0)]] StructuredBuffer<MinecraftImplementationLayoutProbe, Std430DataLayout> minecraftImplementationLayoutProbe;
[[vk::binding(9, 0)]] StructuredBuffer<MinecraftEnvironmentBindingLayoutProbe, Std430DataLayout> minecraftEnvironmentBindingLayoutProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    // Keep the parameter reachable without requiring a writable output resource.
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(minecraftImplementationLayoutProbe[0].values[0].materialsAddress)
                + float(minecraftEnvironmentBindingLayoutProbe[0].values[0].skyInputsAddress);
    }
}

// Each display-chain push-constant struct gets its own tiny entry point below: SPIR-V permits only one
// push-constant block reachable per entry point, so each entry point touches exactly one. They are
// still reported in the same reflection-json pass because
// reflection parameters are enumerated module-wide, not per-entry-point -- see the main() probe
// above for the equivalent structured-buffer case.

[[vk::push_constant]] SkyInputs skyLutPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeSkyLutPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = skyLutPushProbe.celestial.x + skyLutPushProbe.sunUv.x;
    }
}

[[vk::push_constant]] SkyLutPush skyDispatchPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeSkyDispatchPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(skyDispatchPushProbe.destinationImageIndex)
                + skyDispatchPushProbe.inputs.celestial.x;
    }
}
