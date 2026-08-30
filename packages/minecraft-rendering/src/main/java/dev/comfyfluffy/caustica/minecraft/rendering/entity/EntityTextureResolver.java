package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;

/**
 * Resolves one captured Minecraft texture to a retained, sampled 2D Vulkan image subresource.
 * The image and its described mip range remain unchanged until the borrow is closed.
 */
public interface EntityTextureResolver {
    BorrowedMinecraftTexture resolve(MinecraftEntityMesh.Texture texture);
}
