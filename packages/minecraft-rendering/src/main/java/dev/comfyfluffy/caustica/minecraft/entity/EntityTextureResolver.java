package dev.comfyfluffy.caustica.minecraft.entity;

/**
 * Resolves one captured Minecraft texture to a retained, sampled 2D Vulkan image subresource.
 * The image and its described mip range remain unchanged until the borrow is closed.
 */
public interface EntityTextureResolver {
    BorrowedTexture resolve(MinecraftEntityMesh.Texture texture);

    interface BorrowedTexture extends AutoCloseable {
        long vkImage();
        int format();
        int baseMipLevel();
        int mipLevels();
        int imageLayout();
        @Override void close();
    }
}
