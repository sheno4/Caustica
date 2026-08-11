package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MaterialCatalogTest {
    private static final ResourceId A = ResourceId.of("test", "a");
    private static final ResourceId B = ResourceId.of("test", "b");

    @Test
    void catalogOrdersAssetsAndRejectsDuplicateNames() {
        MaterialTextureAsset a = asset(A, MaterialTextureKind.SHARED_ATLAS, image(0xFFFFFFFF));
        MaterialTextureAsset b = asset(B, MaterialTextureKind.SHARED_ATLAS, image(0xFF000000));
        MaterialCatalog catalog = new MaterialCatalog(List.of(b, a), List.of(), 15000.0f);
        assertEquals(List.of(A, B), catalog.atlasAssets().stream().map(MaterialTextureAsset::material).toList());
        assertEquals(15000.0f, catalog.defaultUniformEmissionLuminanceCdM2());
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialCatalog(List.of(a),
                        List.of(asset(A, MaterialTextureKind.STANDALONE, image(0))), 15000.0f));
    }

    @Test
    void defaultUniformEmissionLuminanceMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialCatalog(List.of(), List.of(), 0.0f));
    }

    @Test
    void imageSourceCanBorrowPixelsWhileEachOpenedViewOwnsItsClose() throws Exception {
        int[] pixels = {0xFF123456};
        AtomicInteger closes = new AtomicInteger();
        MaterialImageSource source = () -> new MaterialImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int argb(int x, int y) { return pixels[0]; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        try (MaterialImage view = source.open()) {
            assertEquals(pixels[0], view.argb(0, 0));
            pixels[0] = 0xFFABCDEF;
            assertEquals(pixels[0], view.argb(0, 0));
        }
        assertEquals(1, closes.get());
    }

    @Test
    void atlasReferencesAreStableValueKeys() {
        MaterialUv uv = new MaterialUv(0.25f, 0.5f, 4.0f, 2.0f);
        AtlasMaterialReference first = new AtlasMaterialReference(A, B, uv);
        AtlasMaterialReference second = new AtlasMaterialReference(A, B,
                new MaterialUv(0.25f, 0.5f, 4.0f, 2.0f));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    private static MaterialTextureAsset asset(ResourceId id, MaterialTextureKind kind,
                                              MaterialImageSource source) {
        return new MaterialTextureAsset(id, kind, 1, 1, () -> {
            MaterialImage image = source.open();
            return new MaterialTextureImage() {
                @Override public int width() { return image.width(); }
                @Override public int height() { return image.height(); }
                @Override public int albedoArgb(int x, int y) { return image.argb(x, y); }
                @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
                @Override public void close() { image.close(); }
            };
        }, MaterialUv.IDENTITY, false, false, false,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR);
    }

    private static MaterialImageSource image(int argb) {
        return () -> new MaterialImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int argb(int x, int y) { return argb; }
            @Override public void close() { }
        };
    }
}
