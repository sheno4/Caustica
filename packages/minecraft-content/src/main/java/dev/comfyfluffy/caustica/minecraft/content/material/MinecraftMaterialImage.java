package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.function.IntBinaryOperator;

/** Zero-copy material image view over host-owned pixel storage. */
public final class MinecraftMaterialImage implements MaterialImage {
    private final IntBinaryOperator pixels;
    private final int width;
    private final int height;
    private final Runnable closeAction;

    public MinecraftMaterialImage(IntBinaryOperator pixels, int width, int height, Runnable closeAction) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.closeAction = closeAction;
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
        return pixels.applyAsInt(x, y);
    }

    @Override
    public void close() {
        closeAction.run();
    }
}
