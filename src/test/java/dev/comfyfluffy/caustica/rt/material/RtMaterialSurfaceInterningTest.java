package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtensionRegistry;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialRequest;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialSelector;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData.Int4;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialSurfaceInterningTest {
    @Test
    void everyDefinitionGetsOneBindingWhileContentEqualSurfacesAreInterned() {
        RtMaterialRegistry.CompiledTables tables = new RtMaterialRegistry.CompiledTables(2);
        RtMaterialDesc desc = description(0);
        int first = tables.addDefinition(desc, definition("first", providerData(1)));
        int second = tables.addDefinition(desc, definition("second", providerData(1)));
        assertEquals(2, tables.bindings.size());
        assertEquals(1, tables.surfaces.size());
        assertEquals(tables.bindings.get(first).surface(), tables.bindings.get(second).surface());
    }

    @Test
    void providerWordsParticipateInSurfaceContentInterning() {
        RtMaterialRegistry.CompiledTables tables = new RtMaterialRegistry.CompiledTables(2);
        RtMaterialDesc desc = description(2);
        int first = tables.addDefinition(desc, definition("first", providerData(1)));
        int second = tables.addDefinition(desc, definition("second", providerData(2)));
        assertEquals(2, tables.surfaces.size());
        assertEquals(0, tables.bindings.get(first).surface());
        assertEquals(1, tables.bindings.get(second).surface());
    }

    @Test
    void customSelectionAndAllProviderWordsSurviveDefinitionCompilation() {
        int[] words = {
                0x10203040, 0x11213141, 0x12223242, 0x13233343,
                0x14243444, 0x15253545, 0x16263646, 0x17273747,
                0x18283848, 0x19293949, 0x1a2a3a4a, 0x1b2b3b4b
        };
        MaterialDefinition fallback = definition("custom", MaterialProviderData.ZERO,
                ResourceId.of("test", "default_surface"));
        MinecraftExtensionRegistry extensions = new MinecraftExtensionRegistry();
        extensions.registerMaterialResolver(ResourceId.of("test", "custom_resolver"), 0,
                java.util.List.of(new MinecraftMaterialSelector(
                        ResourceId.of("test", "material"), null)),
                request -> new MinecraftMaterialResolution(
                        definition("custom", new MaterialProviderData(words)),
                        request.fallback().emission(), request.fallback().opacityMicromapRange()));
        extensions.freeze();
        MaterialDefinition definition = extensions.resolve(new MinecraftMaterialRequest(
                ResourceId.of("test", "material"), ResourceId.of("test", "geometry"),
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE,
                texture -> 11, new MinecraftMaterialResolution(
                        fallback, MinecraftMaterialEmission.NONE, null))).definition();
        int implementation = RtMaterialRegistry.resolveSurfaceImplementation(
                definition, surface -> surface.equals(definition.surface()) ? 37 : -1);
        RtMaterialRegistry.CompiledTables tables = new RtMaterialRegistry.CompiledTables(1);

        int binding = tables.addDefinition(description(implementation), definition);

        assertEquals(ResourceId.of("test", "surface"), definition.surface());
        assertEquals(37, MaterialBindingAbi.surfaceImplementation(tables.bindings.get(binding).packed0()));
        var providerData = tables.surfaces.get(tables.bindings.get(binding).surface()).providerData();
        assertEquals(new Int4(words[0], words[1], words[2], words[3]), providerData.words0());
        assertEquals(new Int4(words[4], words[5], words[6], words[7]), providerData.words1());
        assertEquals(new Int4(words[8], words[9], words[10], words[11]), providerData.words2());
    }

    private static MaterialDefinition definition(String path, MaterialProviderData providerData) {
        return definition(path, providerData, ResourceId.of("test", "surface"));
    }

    private static MaterialDefinition definition(String path, MaterialProviderData providerData,
                                                 ResourceId surface) {
        return new MaterialDefinition(new MaterialHandle(ResourceId.of("test", path)),
                1, 1, 1, 0.5f, 0, 1.5f, 0,
                1, 1, 1, 0, 0.8f, 0.8f, 0.8f, 0,
                1, 1, 1, 0, MaterialTopology.SURFACE,
                surface, 0.5f, providerData);
    }

    private static MaterialProviderData providerData(int firstWord) {
        int[] words = new int[MaterialProviderData.WORD_COUNT];
        words[0] = firstWord;
        return new MaterialProviderData(words);
    }

    private static RtMaterialDesc description(int implementation) {
        return new RtMaterialDesc(RtMaterialRegistry.TRANSPORT_SURFACE,
                0.4f, 0.2f, 1.5f, 0.0f, 0.0f, implementation);
    }
}
