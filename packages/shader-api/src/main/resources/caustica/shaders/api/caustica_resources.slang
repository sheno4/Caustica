module caustica_resources;

/**
 * Typed descriptor indices for the Vulkan descriptor heaps bound by the renderer. Java heap allocation
 * returns a range whose `firstIndex` is already in this same unified slot space; no byte-size conversion
 * is part of the public ABI.
 *
 * Compile programs with the `spvDescriptorHeapEXT` capability and unified resource-heap stride.
 * Slang lowers texture and sampler access below to SPV_EXT_descriptor_heap built-ins. Acceleration
 * structure indices are consumed by explicit push-index descriptor mappings at shader creation.
 */
public struct SampledTexture2DIndex {
    public uint value;
};

public struct SamplerIndex {
    public uint value;
};

public struct AccelerationStructureIndex {
    public uint value;
};

/** Resolve a possibly invocation-varying sampled 2D texture descriptor. */
public Texture2D<float4> sampledTexture2D(SampledTexture2DIndex index) {
    Texture2D<float4> texture = ResourceDescriptorHeap[NonUniformResourceIndex(index.value)];
    return texture;
}

/** Resolve a possibly invocation-varying sampler descriptor. */
public SamplerState sampler(SamplerIndex index) {
    SamplerState value = SamplerDescriptorHeap[NonUniformResourceIndex(index.value)];
    return value;
}

/** Sample a texture/sampler pair while keeping descriptor lookup policy in this versioned module. */
public float4 sampleTexture2DLod(SampledTexture2DIndex textureIndex, SamplerIndex samplerIndex,
        float2 coordinates, float lod) {
    Texture2D<float4> texture = sampledTexture2D(textureIndex);
    SamplerState samplerState = sampler(samplerIndex);
    return texture.SampleLevel(samplerState, coordinates, lod);
}
