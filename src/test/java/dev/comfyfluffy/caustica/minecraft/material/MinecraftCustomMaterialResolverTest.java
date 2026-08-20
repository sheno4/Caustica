package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.TextureRegistrar;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtensionRegistry;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftCustomMaterialResolverTest {
    private static final ResourceId MATERIAL = ResourceId.of("test", "material");
    private static final ResourceId GEOMETRY = ResourceId.of("test", "geometry");
    private static final ResourceId DEFAULT_SURFACE = ResourceId.of("caustica", "minecraft_material");
    private static final ResourceId CUSTOM_SURFACE = ResourceId.of("test", "custom_minecraft_surface");

    @Test
    void selectedCustomDefinitionAndOpaqueBlobPublishThroughTheCatalog() {
        MinecraftExtensionRegistry extensions = new MinecraftExtensionRegistry();
        CpuTextureResource customTexture = new CpuTextureResource(1, 1,
                CpuTextureResource.Encoding.LINEAR, new byte[] { -1, -1, -1, -1 });
        class EpochTextureSlot {
            private TextureRegistrar epoch;
            private int slot;

            int resolve(TextureRegistrar textures) {
                if (textures != epoch) {
                    epoch = textures;
                    slot = textures.register(customTexture);
                }
                return slot;
            }
        }
        EpochTextureSlot customTextureSlot = new EpochTextureSlot();
        extensions.registerMaterialResolver(ResourceId.of("test", "custom_material"), 0,
                List.of(new MinecraftMaterialSelector(MATERIAL, GEOMETRY)), request -> {
                    int[] words = {
                            customTextureSlot.resolve(request.textures()),
                            0x11213141, 0x12223242, 0x13233343,
                            0x14243444, 0x15253545, 0x16263646, 0x17273747,
                            0x18283848, 0x19293949, 0x1a2a3a4a, 0x1b2b3b4b
                    };
                    return new MinecraftMaterialResolution(
                            replaceDefinition(request.fallback().definition(), CUSTOM_SURFACE,
                                    new MaterialProviderData(words)),
                            request.fallback().emission(), request.fallback().opacityMicromapRange());
                });
        extensions.freeze();
        AtomicInteger textureRegistrations = new AtomicInteger();
        MinecraftResolvedMaterialCatalog catalog = MinecraftResolvedMaterialCatalog.build(
                List.of(), List.of(), List.of(resource()), pages(), DEFAULT_SURFACE,
                texture -> {
                    textureRegistrations.incrementAndGet();
                    return 23;
                }, extensions);

        var resolved = catalog.resolve(new MinecraftMaterialKey(MATERIAL, GEOMETRY,
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE));

        assertEquals(CUSTOM_SURFACE, resolved.definition().surface());
        assertArrayEquals(new int[] {
                23, 0x11213141, 0x12223242, 0x13233343,
                0x14243444, 0x15253545, 0x16263646, 0x17273747,
                0x18283848, 0x19293949, 0x1a2a3a4a, 0x1b2b3b4b
        }, resolved.definition().providerData().words());
        assertEquals(1, textureRegistrations.get());
    }

    private static MaterialDefinition replaceDefinition(MaterialDefinition source, ResourceId surface,
                                                        MaterialProviderData providerData) {
        return new MaterialDefinition(source.handle(),
                source.baseColorR(), source.baseColorG(), source.baseColorB(),
                source.specularRoughness(), source.baseMetalness(), source.specularIor(),
                source.transmissionWeight(), source.transmissionColorR(), source.transmissionColorG(),
                source.transmissionColorB(), source.subsurfaceWeight(), source.subsurfaceColorR(),
                source.subsurfaceColorG(), source.subsurfaceColorB(), source.subsurfaceScatterAnisotropy(),
                source.emissionColorR(), source.emissionColorG(), source.emissionColorB(),
                source.emissionLuminanceCdM2(), source.topology(), surface, source.alphaCutoff(), providerData);
    }

    private static MinecraftMaterialPageCompiler.Result pages() {
        var compiled = new MinecraftMaterialPageCompiler.CompiledMaterial(MaterialProviderData.ZERO, 0);
        return new MinecraftMaterialPageCompiler.Result(Map.of(MATERIAL, compiled), compiled);
    }

    private static MaterialTextureResource resource() {
        return new MaterialTextureResource(MATERIAL, MaterialTextureKind.STANDALONE,
                new MaterialTextureAnalysisSource(1, 1, 1, () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return 0xffffffff; }
                    @Override public int alphaArgb(int frame, int x, int y) { return 0xffffffff; }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
                    @Override public void close() { }
                }), MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.BASE_COLOR, 1.5f, 0.0f);
    }
}
