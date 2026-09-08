package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void sharedCloseFailureDoesNotSkipTheAlbedoImage() throws Exception {
        var closes = new AtomicInteger();
        var failure = new AssertionError("image close failed");
        MaterialImageSource broken = () -> new MinecraftMaterialImage((x, y) -> -1, 1, 1, () -> {
            closes.incrementAndGet();
            throw failure;
        });
        var bundle = new MinecraftMaterialTextureSource(image(closes), broken, broken, false).open();

        assertSame(failure, assertThrows(AssertionError.class, bundle::close));
        assertEquals(3, closes.get());
    }

    @Test
    void sharedOpenAndCloseFailurePreservesTheOriginalErrorAndReleasesAllImages() {
        var closes = new AtomicInteger();
        var failure = new AssertionError("image failure");
        MaterialImageSource brokenClose = () -> new MinecraftMaterialImage((x, y) -> -1, 1, 1, () -> {
            closes.incrementAndGet();
            throw failure;
        });
        MaterialImageSource brokenOpen = () -> { throw failure; };
        var source = new MinecraftMaterialTextureSource(image(closes), brokenClose, brokenOpen, false);

        assertSame(failure, assertThrows(AssertionError.class, source::open));
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
