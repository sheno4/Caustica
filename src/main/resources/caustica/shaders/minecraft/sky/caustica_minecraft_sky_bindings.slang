module caustica_minecraft_sky_bindings;

import caustica_resources;
import caustica_types;
import caustica_minecraft_sky_atmosphere;

/** Scene-owned descriptor-heap indices and atmosphere state selected by an environment binding. */
public struct MinecraftEnvironmentBinding {
    public SampledTexture2DIndex skyView;
    public SampledTexture2DIndex transmittance;
    public SampledTexture2DIndex celestialsAtlas;
    public SamplerIndex lutSampler;
    public SamplerIndex celestialSampler;
    public uint64_t skyInputsAddress;
};

public MinecraftEnvironmentBinding minecraftEnvironmentBinding(EnvironmentQuery query) {
    return ShaderDataPtr<MinecraftEnvironmentBinding>(query.bindingData)[0];
}

public float4 sampleMinecraftEnvironment(MinecraftEnvironmentBinding binding,
        SampledTexture2DIndex texture, SamplerIndex samplerIndex, float2 coordinates) {
    return sampleTexture2DLod(texture, samplerIndex, coordinates, 0.0);
}
