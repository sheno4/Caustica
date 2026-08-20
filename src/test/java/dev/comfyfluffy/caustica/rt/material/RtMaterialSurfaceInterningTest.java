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

    private static MaterialDefinition definition(String path, MaterialProviderData providerData) {
        return new MaterialDefinition(new MaterialHandle(ResourceId.of("test", path)),
                1, 1, 1, 0.5f, 0, 1.5f, 0,
                1, 1, 1, 0, 0.8f, 0.8f, 0.8f, 0,
                1, 1, 1, 0, MaterialTopology.SURFACE, null, 0.5f, providerData);
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
