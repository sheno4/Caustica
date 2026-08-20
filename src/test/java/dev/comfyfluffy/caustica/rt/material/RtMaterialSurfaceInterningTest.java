package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialSurfaceInterningTest {
    @Test
    void compiledBindingsReuseContentEqualSurfaceRecords() {
        RtMaterialRegistry.CompiledTables tables = new RtMaterialRegistry.CompiledTables(2);
        RtMaterialPageCompiler.Entry entry = new RtMaterialPageCompiler.Entry(
                RtMaterialRegistry.FEATURE_SPEC, 4, 2,
                0.25f, 0.5f, 0.125f, 0.25f,
                0.0f, 0.0f, 1.0f, 1.0f);
        RtMaterialDesc authored = description(RtMaterialDesc.Source.AUTHORED_TEXTURE);
        RtMaterialDesc derived = description(RtMaterialDesc.Source.DERIVED_TEXTURE);

        int authoredBinding = tables.add(authored, entry, 0.5f);
        int derivedBinding = tables.add(derived, entry, 0.5f);

        assertEquals(2, tables.bindings.size(), "binding-indexed metadata stays dense");
        assertEquals(1, tables.surfaces.size(), "content-equal GPU surfaces share one record");
        assertEquals(0, tables.bindings.get(authoredBinding).surface());
        assertEquals(0, tables.bindings.get(derivedBinding).surface());
    }

    @Test
    void providerWordsParticipateInSurfaceContentInterning() {
        RtMaterialRegistry.CompiledTables tables = new RtMaterialRegistry.CompiledTables(2);
        RtMaterialPageCompiler.Entry entry = new RtMaterialPageCompiler.Entry(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1);
        RtMaterialDesc desc = description(RtMaterialDesc.Source.NEUTRAL, 2);
        MaterialDefinition first = definition("first", providerData(1));
        MaterialDefinition second = definition("second", providerData(2));

        int firstBinding = tables.addDefinition(desc, first, entry);
        int secondBinding = tables.addDefinition(desc, second, entry);

        assertEquals(2, tables.surfaces.size());
        assertEquals(0, tables.bindings.get(firstBinding).surface());
        assertEquals(1, tables.bindings.get(secondBinding).surface());
    }

    private static MaterialDefinition definition(String path, MaterialProviderData providerData) {
        return new MaterialDefinition(new MaterialHandle(ResourceId.of("test", path)),
                1, 1, 1, 0.5f, 0, 1.5f, 0,
                1, 1, 1, 0, 0.8f, 0.8f, 0.8f, 0,
                1, 1, 1, 0, MaterialTopology.SURFACE, null, 0.5f, null,
                providerData);
    }

    private static MaterialProviderData providerData(int firstWord) {
        int[] words = new int[MaterialProviderData.WORD_COUNT];
        words[0] = firstWord;
        return new MaterialProviderData(words);
    }

    private static RtMaterialDesc description(RtMaterialDesc.Source source) {
        return description(source, RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
    }

    private static RtMaterialDesc description(RtMaterialDesc.Source source, int implementation) {
        return new RtMaterialDesc(RtMaterialRegistry.TRANSPORT_SURFACE, source,
                RtMaterialRegistry.FEATURE_SPEC, 0.4f, 0.2f, 1.5f, 0.0f,
                0.0f,
                implementation);
    }
}
