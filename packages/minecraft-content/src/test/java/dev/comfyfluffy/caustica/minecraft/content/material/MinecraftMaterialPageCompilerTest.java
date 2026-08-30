package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftMaterialPageCompilerTest {
    @Test
    void emitsNeutralCpuTexturesWithoutOpeningSemanticOnlyPixels() {
        AtomicInteger opens = new AtomicInteger();
        MaterialUv baseUv = new MaterialUv(0.25f, 0.5f, 2.0f, 4.0f);
        MaterialTextureResource resource = new MaterialTextureResource(ResourceId.of("test", "semantic"),
                MaterialTextureKind.STANDALONE, new MaterialTextureAnalysisSource(4, 4, 1, () -> {
            opens.incrementAndGet();
            throw new AssertionError("semantic-only material must not open pixels");
        }), baseUv, false, false, false, OpenPbrColorBinding.BASE_COLOR,
                OpenPbrColorBinding.BASE_COLOR, OpenPbrDefaults.SPECULAR_IOR, 0.0f);

        MinecraftMaterialPageCompiler.Result result = MinecraftMaterialPageCompiler.compile(List.of(resource));

        assertEquals(4, result.textures().size());
        assertEquals(0, opens.get());
        var material = result.material(resource.material());
        assertEquals(MinecraftMaterialPageCompiler.FEATURE_SUBSURFACE_COLOR_BASE
                | MinecraftMaterialPageCompiler.FEATURE_EMISSION_COLOR_BASE, material.features());
        assertEquals(0, material.surface0Texture());
        assertEquals(2, material.surface1Texture());
        assertEquals(1, material.normalTexture());
        assertEquals(3, material.emissionTexture());
        assertEquals(baseUv, material.baseColorUv());
        assertEquals(result.fallback(), result.material(ResourceId.of("test", "missing")));
    }

    @Test
    void emitsCanonicalPageMipChainsAndLogicalTextureOrdinals() {
        MaterialTextureResource resource = new MaterialTextureResource(ResourceId.of("test", "paged"),
                MaterialTextureKind.STANDALONE, new MaterialTextureAnalysisSource(1, 1, 1,
                () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return 0xFFFFFFFF; }
                    @Override public int alphaArgb(int frame, int x, int y) { return 0xFFFFFFFF; }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) {
                        out.specularRoughness = 0.25f;
                    }
                    @Override public void close() { }
                }), MaterialUv.IDENTITY, true, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrDefaults.SPECULAR_IOR, 0.0f);

        var result = MinecraftMaterialPageCompiler.compile(List.of(resource), 16, 16, 1);

        assertEquals(7, result.textures().size(), "four neutral textures plus three material planes");
        assertEquals(5, result.textures().get(4).levels().size());
        var material = result.material(resource.material());
        assertEquals(MinecraftMaterialPageCompiler.FEATURE_SPEC, material.features());
        assertEquals(4, material.surface0Texture());
        assertEquals(6, material.surface1Texture());
        assertEquals(5, material.normalTexture());
        assertEquals(3, material.emissionTexture());
        assertEquals(1.0f / 16.0f, material.materialUv().u());
        assertEquals(1.0f / 16.0f, material.materialUv().inverseDu());
    }
}
