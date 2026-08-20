package dev.comfyfluffy.caustica.minecraft.material;

import com.mojang.blaze3d.platform.NativeImage;

/** Zero-copy material image view over Minecraft's native image storage. */
final class MinecraftMaterialImage implements MaterialImage {
    private final NativeImage image;
    private final int width;
    private final int height;
    private final boolean owned;

    MinecraftMaterialImage(NativeImage image, int width, int height, boolean owned) {
        this.image = image;
        this.width = width;
        this.height = height;
        this.owned = owned;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int argb(int x, int y) {
        return image.getPixel(x, y);
    }

    int rawArgb(int x, int y) {
        return image.getPixel(x, y);
    }

    @Override
    public void close() {
        if (owned) image.close();
    }
}
