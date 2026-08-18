package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.api.provider.MaterialUv;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/** Stateless Minecraft resource and atlas translation at geometry/material call sites. */
public final class MinecraftMaterialLookup {
    private MinecraftMaterialLookup() {
    }

    public static ResourceId material(TextureAtlasSprite sprite) {
        if (sprite == null) return null;
        return resourceId(sprite.contents().name());
    }

    public static ResourceId logicalTexture(Identifier textureLocation) {
        if (textureLocation == null) return null;
        String path = textureLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return ResourceId.of(textureLocation.getNamespace(), path);
    }

    public static AtlasMaterialReference atlasMaterial(TextureAtlasSprite sprite) {
        if (sprite == null) return null;
        return new AtlasMaterialReference(material(sprite), resourceId(sprite.atlasLocation()),
                new MaterialUv(sprite.getU0(), sprite.getV0(), inverseExtent(sprite.getU1() - sprite.getU0()),
                        inverseExtent(sprite.getV1() - sprite.getV0())));
    }

    private static ResourceId resourceId(Identifier id) {
        return ResourceId.of(id.getNamespace(), id.getPath());
    }

    private static float inverseExtent(float extent) {
        return 1.0f / extent;
    }
}
