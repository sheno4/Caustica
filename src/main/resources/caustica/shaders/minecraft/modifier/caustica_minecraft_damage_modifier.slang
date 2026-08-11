module caustica_minecraft_damage_modifier;

import caustica_color;
import caustica_surface_modifier;
import caustica_texture_resources;
import caustica_types;
import caustica_minecraft_damage_bindings;

public struct MinecraftDamageModifier : ISurfaceModifier {
    public void applySurfaceModifier(SurfaceModifierInput input, inout MaterialInput material) {
        int3 cell = int3(floor(input.position - input.geometricNormal * 0.01));
        for (uint index = 0u; index < minecraftDamageModifiers.count; ++index) {
            MinecraftDamageModifierEntry entry = minecraftDamageModifiers.entries[index];
            if (!all(entry.cell == cell)) {
                continue;
            }
            float3 local = input.position - float3(cell);
            float3 axisWeight = abs(input.geometricNormal);
            float2 uv = axisWeight.x >= axisWeight.y && axisWeight.x >= axisWeight.z ? local.zy
                    : axisWeight.y >= axisWeight.z ? local.xz : local.xy;
            Sampler2D texture = baseColorTextures[NonUniformResourceIndex(entry.baseColorTextureIndex)];
            uint width, height;
            texture.GetDimensions(width, height);
            float footprint = max(input.textureFootprint * max(float(width), float(height)), 1.0e-4);
            float lod = clamp(log2(footprint), 0.0, 12.0);
            float3 factor = bt709ToAcesCg(srgbToLinear(texture.SampleLevel(uv, lod).rgb));
            material.baseColor = clamp(material.baseColor * factor, float3(0.0), float3(1.0));
            material.emissionColor = clamp(material.emissionColor * factor, float3(0.0), float3(1.0));
            return;
        }
    }
};
