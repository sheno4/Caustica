module caustica_minecraft_sky_bindings;

import caustica_resources;
import caustica_types;
import caustica_minecraft_sky_atmosphere;

/** Descriptor indices and atmosphere state retained by an environment binding. */
public struct MinecraftEnvironmentBinding {
    public SampledTexture2DIndex skyView;
    public SampledTexture2DIndex transmittance;
    public SampledTexture2DIndex celestialsAtlas;
    public SamplerIndex lutSampler;
    public SamplerIndex celestialSampler;
    public uint64_t skyInputsAddress;
    public uint64_t surfaceShadowInputsAddress;
};

/** Queue-updated inputs for the previous accepted frame's immutable native cloud field. */
public struct MinecraftSurfaceShadowInputs {
    public uint enabled;
    public uint textureIndex;
    public float2 padding;
    public float4 origin;
    public float4 scale;
    public float4 lightDirection;
};

public MinecraftEnvironmentBinding minecraftEnvironmentBinding(EnvironmentQuery query) {
    return ShaderDataPtr<MinecraftEnvironmentBinding>(query.bindingData)[0];
}
