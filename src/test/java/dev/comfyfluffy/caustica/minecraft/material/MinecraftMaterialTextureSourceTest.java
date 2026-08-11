package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.engine.material.MaterialImage;
import dev.comfyfluffy.caustica.engine.material.MaterialImageSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftMaterialTextureSourceTest {
    @Test
    void bundleClosesEveryOpenedImageExactlyOnce() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        MaterialImageSource image = image(closes);
        try (var ignored = new MinecraftMaterialTextureSource(image, image, image, false).open()) { }
        assertEquals(3, closes.get());
    }

    @Test
    void partialOpenFailureClosesOnlyAcquiredImagesExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        MaterialImageSource image = image(closes);
        MaterialImageSource broken = () -> { throw new IOException("broken"); };
        assertThrows(IOException.class,
                () -> new MinecraftMaterialTextureSource(image, image, broken, false).open());
        assertEquals(2, closes.get());
    }

    private static MaterialImageSource image(AtomicInteger closes) {
        return () -> new MaterialImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int argb(int x, int y) { return -1; }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }
}
