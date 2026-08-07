package dev.comfyfluffy.caustica.rt.shader;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.FeatureCategory;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldShaderCompilerTest {
    @Test
    void packagesWorldSourcesWithoutPrecompiledWorldStages() {
        assertNotNull(getClass().getResource("/caustica/shaders/world/primary.rgen.slang"));
        assertNotNull(getClass().getResource("/caustica/shaders/world/indirect_ser.slang"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/world/primary.rgen.spv"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/world/indirect_ser.rgen.spv"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/world/closest_hit.rchit.spv"));
    }

    @Test
    void compilesEveryCompositionGenericWorldStage(@TempDir Path cacheDirectory) throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertSpirv(compiler.compileClosestHit(), 1024);
            assertSpirv(compiler.compileIndirect(false), 1024);
            assertTrue(compiler.composition().rootSource().contains("typealias Sky = LutSky"));
            assertTrue(compiler.composition().rootSource().contains("typealias Surface = BuiltinSurface"));
            assertTrue(compiler.composition().rootSource().contains("typealias Medium = BuiltinMedium"));
        }
        try (WorldShaderCompiler compiler = compiler(cacheDirectory.resolve("ser"))) {
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertSpirv(compiler.compileClosestHit(), 1024);
            assertSpirv(compiler.compileIndirect(true), 1024);
        }
    }

    // Slang treats a module as safe to declare globals only if some entry point compiled in this session
    // plain-imported it, so whether a specialized stage compiles must not depend on which stage ran first.
    // RtComposite compiles indirect BEFORE sky_miss; a session that only ever saw sky_miss first would
    // hide a missing anchor import in every other stage.
    @ParameterizedTest
    @ValueSource(strings = {"indirect", "indirect_ser", "closest_hit", "sky_miss"})
    void everySpecializedStageCompilesFirstInAFreshSession(String stage, @TempDir Path cacheDirectory)
            throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory.resolve(stage))) {
            assertSpirv(switch (stage) {
                case "indirect" -> compiler.compileIndirect(false);
                case "indirect_ser" -> compiler.compileIndirect(true);
                case "closest_hit" -> compiler.compileClosestHit();
                default -> compiler.compileSkyMiss();
            }, 1024);
        }
    }

    @Test
    void repeatedCompositionCompilationIsServedFromMemory(@TempDir Path cacheDirectory) throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            byte[] first = compiler.compileSkyMiss();
            byte[] second = compiler.compileSkyMiss();
            assertArrayEquals(first, second);
            assertTrue(first == second, "second compile should return the cached array instance");
        }
    }

    @Test
    void compilesASelectedClasspathFeatureAndItsTransitiveImport(@TempDir Path cacheDirectory)
            throws Exception {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Identifier featureId = Identifier.fromNamespaceAndPath("test", "sky");
        registry.feature(featureId)
                .title(Component.literal("Test sky"))
                .category(FeatureCategory.SKY)
                .shaderSource(ShaderSource.classpath("/caustica-test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();
        registry.select(Slots.SKY, featureId);

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cacheDirectory, registry.selection())) {
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertTrue(compiler.composition().rootSource().contains("typealias Sky = TestSky"));
            assertTrue(java.nio.file.Files.isRegularFile(cacheDirectory.resolve(
                    "features/test/sky/test_sky_helper.slang")));
        }
    }

    @Test
    void passResourceBindingsAreDiscoveredFromCompositionReflection(@TempDir Path cacheDirectory)
            throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            compiler.compileSkyMiss();
            // caustica_sky_bindings.slang, imported by the built-in LutSky, declares exactly these four:
            // its two LUTs, its per-frame sky inputs, and the celestials atlas. The uniform-buffer kind is
            // the point: reflection distinguishes the descriptor kinds a pass declares, not just images.
            assertEquals(Map.of(
                    "skyView", new WorldShaderCompiler.PassResourceBinding(0,
                            WorldShaderCompiler.PassResourceKind.SAMPLED_IMAGE),
                    "transmittance", new WorldShaderCompiler.PassResourceBinding(1,
                            WorldShaderCompiler.PassResourceKind.SAMPLED_IMAGE),
                    "skyInputs", new WorldShaderCompiler.PassResourceBinding(2,
                            WorldShaderCompiler.PassResourceKind.UNIFORM_BUFFER),
                    "celestialsAtlas", new WorldShaderCompiler.PassResourceBinding(3,
                            WorldShaderCompiler.PassResourceKind.SAMPLED_IMAGE)),
                    compiler.passResourceBindings());
        }
    }

    @Test
    void passResourceKindIsReadFromReflectedTypeShape(@TempDir Path cacheDirectory) throws Exception {
        // Shapes confirmed empirically against slangc -reflection-json (undocumented elsewhere): a plain
        // resource has type.kind == "resource", split by baseShape ("texture2D" vs "structuredBuffer")
        // and, for images only, combined (sampled) vs access == "readWrite" (storage) — StructuredBuffer
        // and RWStructuredBuffer both reflect as "structuredBuffer" and both lower to
        // VK_DESCRIPTOR_TYPE_STORAGE_BUFFER regardless of access. ConstantBuffer<T> is its own top-level
        // type.kind == "constantBuffer".
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            String json = "{\"parameters\":["
                    + param("storageImage", 0, "{\"kind\":\"resource\",\"baseShape\":\"texture2D\",\"access\":\"readWrite\"}")
                    + ","
                    + param("readBuf", 1, "{\"kind\":\"resource\",\"baseShape\":\"structuredBuffer\"}")
                    + ","
                    + param("rwBuf", 2, "{\"kind\":\"resource\",\"baseShape\":\"structuredBuffer\",\"access\":\"readWrite\"}")
                    + ","
                    + param("constBuf", 3, "{\"kind\":\"constantBuffer\"}")
                    + "]}";

            compiler.collectPassResourceBindings("fake_stage", json);

            Map<String, WorldShaderCompiler.PassResourceBinding> bindings = compiler.passResourceBindings();
            assertEquals(WorldShaderCompiler.PassResourceKind.STORAGE_IMAGE, bindings.get("storageImage").kind());
            assertEquals(WorldShaderCompiler.PassResourceKind.STORAGE_BUFFER, bindings.get("readBuf").kind());
            assertEquals(WorldShaderCompiler.PassResourceKind.STORAGE_BUFFER, bindings.get("rwBuf").kind());
            assertEquals(WorldShaderCompiler.PassResourceKind.UNIFORM_BUFFER, bindings.get("constBuf").kind());
        }
    }

    private static String param(String name, int index, String type) {
        return "{\"name\":\"" + name + "\",\"binding\":{\"kind\":\"descriptorTableSlot\",\"index\":" + index
                + ",\"space\":2},\"type\":" + type + "}";
    }

    @Test
    void aSecondBindingClaimingAnAlreadyTakenIndexFails(@TempDir Path cacheDirectory) throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            compiler.compileSkyMiss(); // registers skyView@0, transmittance@1 for real
            String colliding = "{\"parameters\":[{\"name\":\"bogus\","
                    + "\"binding\":{\"kind\":\"descriptorTableSlot\",\"index\":0,\"space\":2},"
                    + "\"type\":{\"kind\":\"resource\",\"baseShape\":\"texture2D\",\"combined\":true}}]}";

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> compiler.collectPassResourceBindings("fake_stage", colliding));
            assertTrue(e.getMessage().contains("bogus"));
            assertTrue(e.getMessage().contains("skyView"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "primary.rgen.slang", "guide.rmiss.slang", "any_hit.rahit.slang"})
    void compilesEveryPlainWorldStage(String moduleFileName, @TempDir Path cacheDirectory)
            throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            assertSpirv(compiler.compilePlain(moduleFileName, WorldShaderCompiler.ENTRY_POINT), 256);
        }
    }

    private static WorldShaderCompiler compiler(Path cacheDirectory) throws Exception {
        return WorldShaderCompiler.create(cacheDirectory, CausticaRegistry.withBuiltins().selection());
    }

    private static void assertSpirv(byte[] spirv, int minimumBytes) {
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(spirv.length > minimumBytes, "expected substantial SPIR-V, got " + spirv.length);
    }
}
