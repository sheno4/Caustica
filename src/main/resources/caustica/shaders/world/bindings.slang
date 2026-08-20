// Complete descriptor and push-constant interface for the world ray-tracing pipeline.
// Every world stage imports this module instead of redeclaring its own subset.

module bindings;

import caustica_texture_resources;
import world_common;

[[vk::push_constant]] public WorldPushConstants pc;

[[vk::binding(0, 0)]] public RaytracingAccelerationStructure topLevelAS;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;

[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNormal;
[[vk::binding(4, 0)]] [format("rgba16f")] public RWTexture2D<float4> gAlbedo;
[[vk::binding(5, 0)]] [format("r32f")] public RWTexture2D<float> gDepth;
[[vk::binding(6, 0)]] [format("rg16f")] public RWTexture2D<float2> gMotion;
[[vk::binding(7, 0)]] [format("rgba16f")] public RWTexture2D<float4> gSpecAlbedo;
[[vk::binding(8, 0)]] [format("rg16f")] public RWTexture2D<float2> gSpecMotion;

// Binding 2 is deliberately unbound. Set 0 is stable, so the guide bindings remain at 3 through 8.

// Canonical material pages. Source formats are decoded into these by RtMaterialPageCompiler, so the channels
// below are OpenPBR parameters, not source-format bytes. Every channel is a unorm8.
//
//   surface0 — specular_roughness (perceptual r), base_metalness, emission mask, subsurface_weight
//   normal   — tangent-space normal xy; z is reconstructed. b unused, a authored height (unused)
//   surface1 — rgb: base_color on a metal texel, neutral on a dielectric one
//              a: specular_ior, encoded as (ior - 1) / (ior + 1) — see canonicalIor
//   emission — linear BT.709 emissive RGB multiplier; a unused
[[vk::binding(1, 1)]] public Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] public Sampler2D materialNormalTex[];
[[vk::binding(3, 1)]] public Sampler2D materialSurface1Tex[];
[[vk::binding(4, 1)]] public Sampler2D materialEmissionTex[];
