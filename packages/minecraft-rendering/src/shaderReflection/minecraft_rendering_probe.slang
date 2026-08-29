import caustica_minecraft_material;
import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_sky_bindings;
import caustica_minecraft_sky_lut_types;

struct MinecraftImplementationLayoutProbe { MinecraftImplementationData values[2]; };
struct MinecraftMaterialLayoutProbe { MinecraftMaterialRecord values[2]; };
struct MinecraftPrimitiveLayoutProbe { MinecraftPrimitiveData values[2]; };
struct MinecraftInstanceLayoutProbe { MinecraftInstanceData values[2]; };

[[vk::binding(3, 0)]] StructuredBuffer<MinecraftImplementationLayoutProbe, Std430DataLayout> minecraftImplementationLayoutProbe;
struct MinecraftEnvironmentBindingLayoutProbe { MinecraftEnvironmentBinding values[2]; };
[[vk::binding(9, 0)]] StructuredBuffer<MinecraftEnvironmentBindingLayoutProbe, Std430DataLayout> minecraftEnvironmentBindingLayoutProbe;
[[vk::binding(0, 0)]] StructuredBuffer<MinecraftMaterialLayoutProbe, Std430DataLayout> minecraftMaterialLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<MinecraftPrimitiveLayoutProbe, Std430DataLayout> minecraftPrimitiveLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<MinecraftInstanceLayoutProbe, Std430DataLayout> minecraftInstanceLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(minecraftImplementationLayoutProbe[0].values[0].materialsAddress)
                + minecraftMaterialLayoutProbe[0].values[0].specularRoughness
                + minecraftPrimitiveLayoutProbe[0].values[0].primitiveEmission
                + minecraftInstanceLayoutProbe[0].values[0].damageStrength;
    }
}

[[vk::push_constant]] SkyInputs skyLutPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeSkyLutPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = skyLutPushProbe.celestial.x + skyLutPushProbe.sunUv.x; }
}

[[vk::push_constant]] SkyLutPush skyDispatchPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeSkyDispatchPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = float(skyDispatchPushProbe.destinationImageIndex) + skyDispatchPushProbe.inputs.celestial.x; }
}
