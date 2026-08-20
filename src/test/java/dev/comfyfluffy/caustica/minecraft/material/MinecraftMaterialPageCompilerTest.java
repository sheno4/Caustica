package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftMaterialPageCompilerTest {
    @Test
    void registersNeutralTexturesOnceAndPacksTheirSlotsIntoFallbackRecords() {
        AtomicInteger opens = new AtomicInteger();
        MaterialUv baseUv = new MaterialUv(0.25f, 0.5f, 2.0f, 4.0f);
        MaterialTextureResource resource = new MaterialTextureResource(ResourceId.of("test", "semantic"),
                MaterialTextureKind.STANDALONE, new MaterialTextureAnalysisSource(4, 4, 1, () -> {
            opens.incrementAndGet();
            throw new AssertionError("semantic-only material must not open pixels");
        }), baseUv, false, false, false, OpenPbrColorBinding.BASE_COLOR,
                OpenPbrColorBinding.BASE_COLOR, OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, 0.0f);
        List<CpuTextureResource> registered = new ArrayList<>();

        MinecraftMaterialPageCompiler.Result result = MinecraftMaterialPageCompiler.compile(
                List.of(resource), texture -> {
                    registered.add((CpuTextureResource) texture);
                    return registered.size();
                });

        assertEquals(4, registered.size());
        assertEquals(0, opens.get());
        var material = result.material(resource.material());
        assertEquals(MinecraftMaterialPageCompiler.FEATURE_SUBSURFACE_COLOR_BASE
                | MinecraftMaterialPageCompiler.FEATURE_EMISSION_COLOR_BASE, material.features());
        assertEquals(material.features(), material.providerData().word(0));
        assertEquals(1 | 3 << 16, material.providerData().word(1));
        assertEquals(2 | 4 << 16, material.providerData().word(2));
        assertEquals(Float.floatToRawIntBits(baseUv.u()), material.providerData().word(7));
        assertEquals(Float.floatToRawIntBits(baseUv.inverseDv()), material.providerData().word(10));
        assertEquals(0, material.providerData().word(11));
        assertEquals(result.fallback(), result.material(ResourceId.of("test", "missing")));
    }

    @Test
    void rejectsSlotsThatCannotFitTheProviderBlob() {
        assertThrows(IllegalStateException.class, () -> MinecraftMaterialPageCompiler.compile(
                List.of(), texture -> 65536));
    }

    @Test
    void registersCanonicalPageMipChainsAndPacksPageSlots() {
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
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, 0.0f);
        List<CpuTextureResource> registered = new ArrayList<>();

        var result = MinecraftMaterialPageCompiler.compile(List.of(resource), texture -> {
            registered.add((CpuTextureResource) texture);
            return registered.size();
        }, 16, 16, 1);

        assertEquals(7, registered.size(), "four neutral textures plus three populated material channels");
        assertEquals(5, registered.get(4).mipLevels().size());
        var material = result.material(resource.material());
        assertEquals(MinecraftMaterialPageCompiler.FEATURE_SPEC, material.features());
        assertEquals(5 | 7 << 16, material.providerData().word(1));
        assertEquals(6 | 4 << 16, material.providerData().word(2));
        assertEquals(Float.floatToRawIntBits(1.0f / 16.0f), material.providerData().word(3));
        assertEquals(Float.floatToRawIntBits(1.0f / 16.0f), material.providerData().word(5));
    }
}
