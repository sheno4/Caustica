package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftMaterialPageCompilerImageLifetimeTest {
    @Test
    void baseColorOnlyStandaloneNeedsNoCanonicalPageChannels() {
        int[] pixels = {0xFF204060};
        AtomicInteger closes = new AtomicInteger();
        MaterialTextureResource resource = new MaterialTextureResource(ResourceId.of("test", "owned"),
                MaterialTextureKind.STANDALONE, new MaterialTextureAnalysisSource(1, 1, 1,
                        () -> new MaterialTextureImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int albedoArgb(int x, int y) { return pixels[0]; }
            @Override public int alphaArgb(int frame, int x, int y) { return pixels[0]; }
            @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
            @Override public void close() { closes.incrementAndGet(); }
        }), MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrDefaults.SPECULAR_IOR, 1.0f);

        assertEquals(0, MinecraftMaterialPageCompiler.pageChannels(0));
        assertEquals(MaterialTextureKind.STANDALONE, resource.kind());
        assertEquals(MaterialUv.IDENTITY, resource.albedoUv());
        assertEquals(0, closes.get(), "base colour no longer has a CPU statistics walk");
    }

    @Test
    void compilerClosesImageViewWhenPixelAccessFails() {
        AtomicInteger closes = new AtomicInteger();
        MaterialTextureResource resource = new MaterialTextureResource(ResourceId.of("test", "broken"),
                MaterialTextureKind.SHARED_ATLAS, new MaterialTextureAnalysisSource(1, 1, 1,
                        () -> new MaterialTextureImage() {
            @Override public int width() { return 1; }
            @Override public int height() { return 1; }
            @Override public int albedoArgb(int x, int y) { throw new IllegalStateException("broken image"); }
            @Override public int alphaArgb(int frame, int x, int y) { return albedoArgb(x, y); }
            @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
            @Override public void close() { closes.incrementAndGet(); }
        }), MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrDefaults.SPECULAR_IOR, 1.0f);

        assertThrows(IllegalStateException.class, () -> MaterialTextureAnalyzer.decode(
                resource.analysisSource(), OpenPbrColorBinding.BASE_COLOR, 0));
        assertEquals(1, closes.get());
    }

    @Test
    void decodeMultipliesEmissionColorByLinearBaseColor() throws Exception {
        MaterialTextureAnalysisSource source = new MaterialTextureAnalysisSource(1, 1, 1,
                () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return 0xFF804020; }
                    @Override public int alphaArgb(int frame, int x, int y) { return albedoArgb(x, y); }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) {
                        out.emissionWeight = 0.75f;
                        out.emissionColorR = 0.5f;
                        out.emissionColorG = 0.25f;
                        out.emissionColorB = 1.0f;
                    }
                    @Override public void close() { }
                });

        MaterialTextureAnalyzer.Decoded decoded = MaterialTextureAnalyzer.decode(
                source, OpenPbrColorBinding.BASE_COLOR, 0);
        MaterialTextureLevels.Level level = decoded.levels().getFirst();

        assertEquals(0.75f, level.surface0()[2], 1.0e-6f);
        assertEquals(0.10793025f, level.emissionColor()[0], 1.0e-6f);
        assertEquals(0.012817365f, level.emissionColor()[1], 1.0e-6f);
        assertEquals(0.014443844f, level.emissionColor()[2], 1.0e-6f);
    }

    @Test
    void oversizedResourceIsRejectedBeforeOpeningItsImage() {
        AtomicInteger opens = new AtomicInteger();
        MaterialTextureResource resource = new MaterialTextureResource(ResourceId.of("test", "oversized"),
                MaterialTextureKind.STANDALONE, new MaterialTextureAnalysisSource(8192, 1, 1, () -> {
            opens.incrementAndGet();
            throw new AssertionError("oversized image must not be opened");
        }), MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrDefaults.SPECULAR_IOR, 1.0f);

        assertFalse(MinecraftMaterialPageCompiler.eligibleForPageCompilation(resource));
        assertEquals(0, opens.get());
    }
}
