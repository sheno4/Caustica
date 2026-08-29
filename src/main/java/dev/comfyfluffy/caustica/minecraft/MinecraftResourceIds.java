package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/** Converts Minecraft host resource handles into reusable content identifiers. */
public final class MinecraftResourceIds {
    private MinecraftResourceIds() { }

    public static ResourceId material(TextureAtlasSprite sprite) {
        return sprite == null ? null : resourceId(sprite.contents().name());
    }

    public static ResourceId logicalTexture(Identifier textureLocation) {
        if (textureLocation == null) return null;
        String path = textureLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return ResourceId.of(textureLocation.getNamespace(), path);
    }

    public static ResourceId resourceId(Identifier id) {
        return ResourceId.of(id.getNamespace(), id.getPath());
    }
}
