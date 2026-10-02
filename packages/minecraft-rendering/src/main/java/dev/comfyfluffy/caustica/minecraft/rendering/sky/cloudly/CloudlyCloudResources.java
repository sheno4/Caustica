package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;

import java.util.Map;

/** Producer claim on one ready immutable source-pack texture and descriptor revision. */
public final class CloudlyCloudResources implements AutoCloseable {
    private final CloudlySourcePack source;
    private final Map<Integer, Texture> textures;
    private final GpuDescriptorIndex.Sampler sampler;
    private final ResourceOwner owner;

    CloudlyCloudResources(CloudlySourcePack source, Map<Integer, Texture> textures,
                          GpuDescriptorIndex.Sampler sampler, ResourceOwner owner) {
        this.source = source;
        this.textures = Map.copyOf(textures);
        this.sampler = sampler;
        this.owner = owner;
    }

    public CloudlySourcePack source() { return source; }
    public Map<Integer, Texture> textures() { return textures; }
    public Texture texture(int sourceTextureId) {
        Texture texture = textures.get(sourceTextureId);
        if (texture == null) throw new IllegalArgumentException("Unknown Cloudly texture id " + sourceTextureId);
        return texture;
    }
    public GpuDescriptorIndex.Sampler samplerIndex() { return sampler; }
    /** Retain for each root buffer, captured frame, or GPU job that references these descriptors. */
    public ResourceOwner retain() { return owner.retain(); }
    @Override public void close() { owner.close(); }

    public record Texture(int textureId, int width, int height, int depth, int mipLevels,
                          GpuDescriptorIndex.Resource sampledIndex) { }
}
