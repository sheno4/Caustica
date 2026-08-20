package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.TextureRegistrar;

import java.util.Objects;

/**
 * Host facts available while one finite Minecraft material definition is being resolved. Geometry is
 * absent for the texture-wide case. Every profile, topology, and geometry request in one material epoch
 * shares the same registrar instance, and its slots are epoch-local. A resolver should register a resource
 * only when it claims a request, then cache and reuse that slot for the registrar identity; registering per
 * variant consumes one descriptor slot for every member of the finite cross-product.
 */
public record MinecraftMaterialRequest(ResourceId material, ResourceId geometry,
                                       MinecraftMaterialProfile profile,
                                       MaterialTopology requestedTopology,
                                       TextureRegistrar textures,
                                       MinecraftMaterialResolution fallback) {
    public MinecraftMaterialRequest {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(requestedTopology, "requestedTopology");
        Objects.requireNonNull(textures, "textures");
        Objects.requireNonNull(fallback, "fallback");
    }
}
