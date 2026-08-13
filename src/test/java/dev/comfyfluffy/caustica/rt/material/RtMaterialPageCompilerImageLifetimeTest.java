package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.engine.material.MaterialUv;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.engine.material.OpenPbrTextureTexel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialPageCompilerImageLifetimeTest {
    @Test
    void compilerClosesEachOwnedImageViewAfterScanningWithoutCopyingItsBackingPixels() throws Exception {
        int[] pixels = {0xFF204060};
        AtomicInteger closes = new AtomicInteger();
        MaterialTextureAsset asset = new MaterialTextureAsset(ResourceId.of("test", "owned"),
                MaterialTextureKind.STANDALONE, 1, 1, () -> new MaterialTextureImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int albedoArgb(int x, int y) { return pixels[0]; }
            @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
            @Override public void close() { closes.incrementAndGet(); }
        }, MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR);

        RtMaterialPageCompiler.AlbedoStats stats = RtMaterialPageCompiler.scanAlbedo(asset, 16);

        assertEquals(1, closes.get());
        assertEquals(0x20 / 255.0f, stats.averageR(), 1.0e-6f);
        assertEquals(0x40 / 255.0f, stats.averageG(), 1.0e-6f);
        assertEquals(0x60 / 255.0f, stats.averageB(), 1.0e-6f);
        assertEquals(1.0f, stats.averageA(), 1.0e-6f);
        assertEquals(1.0f, stats.uniformEmissionSummary().averageR(), 1.0e-6f);
        assertEquals(1.0f, stats.uniformEmissionSummary().averageG(), 1.0e-6f);
        assertEquals(1.0f, stats.uniformEmissionSummary().averageB(), 1.0e-6f);
        assertEquals(1.0f, stats.uniformEmissionFootprint().r(0, 0), 1.0e-6f);
        assertEquals(16, stats.uniformEmissionFootprint().resolution());
        assertEquals(256, stats.uniformEmissionFootprint().sampleCount());
    }

    @Test
    void compilerClosesImageViewWhenPixelAccessFails() {
        AtomicInteger closes = new AtomicInteger();
        MaterialTextureAsset asset = new MaterialTextureAsset(ResourceId.of("test", "broken"),
                MaterialTextureKind.SHARED_ATLAS, 1, 1, () -> new MaterialTextureImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int albedoArgb(int x, int y) { throw new IllegalStateException("broken image"); }
            @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
            @Override public void close() { closes.incrementAndGet(); }
        }, MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR);

        assertThrows(IllegalStateException.class, () -> RtMaterialPageCompiler.scanAlbedo(asset, 16));
        assertEquals(1, closes.get());
    }
}
