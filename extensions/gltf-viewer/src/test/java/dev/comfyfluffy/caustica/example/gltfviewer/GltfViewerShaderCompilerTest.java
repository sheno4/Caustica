package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.rt.shader.WorldShaderCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GltfViewerShaderCompilerTest {
    @Test
    void gltfSurfaceAndCoverageCompileThroughRuntimeSpecializedStages(@TempDir Path cacheDirectory)
            throws Exception {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new GltfViewerExtension().register(registry);

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cacheDirectory, registry.selection())) {
            int material = registry.surfaceIndex(GltfViewerExtension.MATERIAL_SURFACE);
            int portal = registry.surfaceIndex(GltfViewerExtension.PROCEDURAL_SURFACE);
            String root = compiler.composition().rootSource();
            assertTrue(root.contains("case " + material + "u: { GltfViewerMaterialSurface s;"));
            assertTrue(root.contains("case " + material + "u: { GltfViewerMaterialCoverage c;"));
            assertTrue(root.contains("case " + portal + "u: { GltfViewerPortalSurface s;"));
            assertTrue(root.contains("case " + portal + "u: { GltfViewerMaterialCoverage c;"));
            assertSpirv(compiler.compileClosestHit());
            assertSpirv(compiler.compileRadianceAnyHit());
            assertSpirv(compiler.compileShadowAnyHit());
        }
    }

    private static void assertSpirv(byte[] code) {
        assertTrue(code.length >= 256);
        assertEquals(0x07230203,
                ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }
}
