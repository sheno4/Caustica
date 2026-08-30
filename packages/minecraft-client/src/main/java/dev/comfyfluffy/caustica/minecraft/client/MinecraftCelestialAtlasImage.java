package dev.comfyfluffy.caustica.minecraft.client;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.comfyfluffy.caustica.minecraft.rendering.CelestialAtlasImage;

import java.util.Objects;

/** Adapts Minecraft's Vulkan texture lifetime to the celestial atlas image contract. */
final class MinecraftCelestialAtlasImage implements CelestialAtlasImage {
    private final VulkanGpuTexture texture;

    MinecraftCelestialAtlasImage(VulkanGpuTexture texture) {
        this.texture = Objects.requireNonNull(texture, "texture");
    }

    @Override public long vkImage() { return texture.vkImage(); }

    @Override public void retainViews() { texture.addViews(); }

    @Override public void releaseViews() { texture.removeViews(); }
}
