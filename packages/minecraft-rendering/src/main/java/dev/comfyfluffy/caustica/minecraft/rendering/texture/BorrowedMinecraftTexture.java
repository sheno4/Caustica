package dev.comfyfluffy.caustica.minecraft.rendering.texture;

/** A retained Vulkan image subresource borrowed from Minecraft's texture lifetime. */
public interface BorrowedMinecraftTexture extends AutoCloseable {
    long vkImage();
    int format();
    int baseMipLevel();
    int mipLevels();
    int imageLayout();
    @Override void close();
}
