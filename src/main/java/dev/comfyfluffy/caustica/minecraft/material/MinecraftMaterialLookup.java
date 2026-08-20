package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
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

    private static ResourceId resourceId(Identifier id) {
        return ResourceId.of(id.getNamespace(), id.getPath());
    }

}
