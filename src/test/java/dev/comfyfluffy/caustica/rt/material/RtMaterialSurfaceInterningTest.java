package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialSurfaceInterningTest {
    @Test
    void compiledBindingsReuseContentEqualSurfaceRecords() {
        RtMaterialRegistry.CompiledTables tables = new RtMaterialRegistry.CompiledTables(2);
        RtMaterialPageCompiler.Entry entry = new RtMaterialPageCompiler.Entry(
                RtMaterialRegistry.FEATURE_SPEC, 4, 2,
                0.25f, 0.5f, 0.125f, 0.25f,
                0.0f, 0.0f, 1.0f, 1.0f,
                0.6f, 0.7f, 0.8f, 1.0f);
        RtMaterialDesc authored = description(RtMaterialDesc.Source.AUTHORED_TEXTURE);
        RtMaterialDesc derived = description(RtMaterialDesc.Source.DERIVED_TEXTURE);

        int authoredBinding = tables.add(authored, entry.average(), entry, 0.5f);
        int derivedBinding = tables.add(derived, entry.average(), entry, 0.5f);

        assertEquals(2, tables.bindings.size(), "binding-indexed metadata stays dense");
        assertEquals(1, tables.surfaces.size(), "content-equal GPU surfaces share one record");
        assertEquals(0, tables.bindings.get(authoredBinding).surface());
        assertEquals(0, tables.bindings.get(derivedBinding).surface());
    }

    private static RtMaterialDesc description(RtMaterialDesc.Source source) {
        return new RtMaterialDesc(RtMaterialRegistry.TRANSPORT_SURFACE, source,
                RtMaterialRegistry.FEATURE_SPEC, 0.4f, 0.2f, 1.5f, 0.0f,
                0.0f,
                RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
    }
}
