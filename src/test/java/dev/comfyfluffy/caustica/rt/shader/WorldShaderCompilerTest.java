package dev.comfyfluffy.caustica.rt.shader;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.FeatureCategory;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldShaderCompilerTest {
    private static final ResourceId TEST_SURFACE = ResourceId.of("test", "surface");

    @Test
    void packagesWorldSourcesWithoutPrecompiledWorldStages() {
        assertNotNull(getClass().getResource("/caustica/shaders/world/primary_rgen.slang"));
        assertNotNull(getClass().getResource("/caustica/shaders/world/indirect_ser.slang"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/world/primary_rgen.spv"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/world/indirect_ser.rgen.spv"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/world/closest_hit.rchit.spv"));
    }

    @Test
    void compilesEveryCompositionGenericWorldStage(@TempDir Path cacheDirectory) throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            assertSpirv(compiler.compilePrimary(), 1024);
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertSpirv(compiler.compileClosestHit(), 1024);
            assertSpirv(compiler.compileIndirect(false), 1024);
        }
        try (WorldShaderCompiler compiler = compiler(cacheDirectory.resolve("ser"))) {
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertSpirv(compiler.compileClosestHit(), 1024);
            assertSpirv(compiler.compileIndirect(true), 1024);
        }
    }

    @Test
    void isolatedCompilerCompilesEveryWorldStage(@TempDir Path cacheDirectory) throws Exception {
        try (WorldShaderCompiler compiler = WorldShaderCompiler.createIsolated(
                cacheDirectory, dev.comfyfluffy.caustica.TestRegistries.withBuiltins().selection())) {
            List<byte[]> stages = List.of(
                    compiler.compilePrimary(),
                    compiler.compileIndirect(false),
                    compiler.compileSkyMiss(),
                    compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT),
                    compiler.compileClosestHit(),
                    compiler.compilePlain("radiance_any_hit.rahit.slang", WorldShaderCompiler.ENTRY_POINT),
                    compiler.compilePlain("shadow_any_hit.rahit.slang", WorldShaderCompiler.ENTRY_POINT));
            for (byte[] stage : stages) {
                assertSpirv(stage, 256);
            }
            // Accumulated across every stage compiled in this session, so it spans every registered
            // feature's own set-2 declarations rather than just the sky the miss stage reached.
            assertEquals(Set.of("skyView", "transmittance", "skyInputs", "celestialsAtlas",
                    "minecraftDamageModifiers"), compiler.passResourceBindings().keySet());
        }
    }

    @Test
    void compilesMinecraftEndPortalSurface(@TempDir Path cacheDirectory) throws Exception {
        CausticaRegistry registry = dev.comfyfluffy.caustica.TestRegistries.withBuiltins();
        try (WorldShaderCompiler compiler = WorldShaderCompiler.createIsolated(
                cacheDirectory, registry.selection())) {
            assertSpirv(compiler.compileSpecialized(
                    WorldShaderCompiler.CLOSEST_HIT_MODULE, WorldShaderCompiler.ENTRY_POINT), 1024);
        }
    }

    // Slang treats a module as safe to declare globals only if some entry point compiled in this session
    // plain-imported it, so whether a specialized stage compiles must not depend on which stage ran first.
    // RtComposite compiles indirect BEFORE sky_miss; a session that only ever saw sky_miss first would
    // hide a missing anchor import in every other stage.
    @ParameterizedTest
    @ValueSource(strings = {"primary", "indirect", "indirect_ser", "closest_hit", "sky_miss"})
    void everySpecializedStageCompilesFirstInAFreshSession(String stage, @TempDir Path cacheDirectory)
            throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory.resolve(stage))) {
            assertSpirv(switch (stage) {
                case "primary" -> compiler.compilePrimary();
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
        CausticaRegistry registry = dev.comfyfluffy.caustica.TestRegistries.withBuiltins();
        ResourceId featureId = ResourceId.of("test", "sky");
        registry.feature(featureId)
                .title(DisplayText.literal("Test sky"))
                .category(FeatureCategory.SKY)
                .shaderSource(ShaderSource.classpath("/caustica-test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();
        registry.select(Slots.SKY, featureId);

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cacheDirectory, registry.selection())) {
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertTrue(java.nio.file.Files.isRegularFile(cacheDirectory.resolve(
                    "features/test/sky/test_sky_helper.slang")));
        }
    }

    /**
     * A registered implementation becomes a case in the generated switch, keyed by the index materials
     * pack into their binding — the engine itself never names it.
     */
    @Test
    void aRegisteredSurfaceImplementationBecomesADispatchCase(@TempDir Path cacheDirectory)
            throws Exception {
        CausticaRegistry registry = registryWithTestSurface("test_surface", "TestSurface");

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cacheDirectory, registry.selection())) {
            assertSpirv(compiler.compilePrimary(), 1024);
            assertSpirv(compiler.compileClosestHit(), 1024);
            assertSpirv(compiler.compileIndirect(false), 1024);
        }
    }

    /**
     * One bad third-party implementation must not take the world pipeline with it. It keeps its index —
     * renumbering would repoint every material compiled against the old order — and the switch resolves
     * that index to the built-in surface instead.
     */
    @Test
    void aSurfaceImplementationThatDoesNotCompileFallsBackToTheBuiltIn(@TempDir Path cacheDirectory)
            throws Exception {
        CausticaRegistry registry = registryWithTestSurface("test_surface_broken", "BrokenSurface");

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cacheDirectory, registry.selection())) {
            assertSpirv(compiler.compileClosestHit(), 1024);
        }
    }

    @Test
    void surfaceModifiersDispatchSequentiallyAndIsolateBrokenImplementations(@TempDir Path cacheDirectory)
            throws Exception {
        CausticaRegistry registry = dev.comfyfluffy.caustica.TestRegistries.withBuiltins();
        registry.feature(ResourceId.of("test", "modifiers"))
                .shaderSource(ShaderSource.classpath("/caustica-test/shaders"))
                .surfaceModifier(ResourceId.of("test", "first"), "test_surface_modifiers", "FirstModifier")
                .surfaceModifier(ResourceId.of("test", "broken"), "test_surface_modifier_broken", "BrokenModifier")
                .surfaceModifier(ResourceId.of("test", "second"), "test_surface_modifiers", "SecondModifier")
                .register();

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cacheDirectory, registry.selection())) {
            assertSpirv(compiler.compileClosestHit(), 1024);
        }
    }

    private static CausticaRegistry registryWithTestSurface(String module, String type) {
        CausticaRegistry registry = dev.comfyfluffy.caustica.TestRegistries.withBuiltins();
        registry.feature(TEST_SURFACE)
                .title(DisplayText.literal("Test surface"))
                .category(FeatureCategory.GENERAL)
                .shaderSource(ShaderSource.classpath("/caustica-test/shaders"))
                .surface(TEST_SURFACE, module, type)
                .register();
        return registry;
    }

    @Test
    void passResourceBindingsAreDiscoveredFromCompositionReflection(@TempDir Path cacheDirectory)
            throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            compiler.compileSkyMiss();
            // The selected Minecraft feature declares the sky inputs and projected-damage modifier inputs.
            assertEquals(Map.of(
                    "skyView", new WorldShaderCompiler.PassResourceBinding(0,
                            WorldShaderCompiler.PassResourceKind.SAMPLED_IMAGE),
                    "transmittance", new WorldShaderCompiler.PassResourceBinding(1,
                            WorldShaderCompiler.PassResourceKind.SAMPLED_IMAGE),
                    "skyInputs", new WorldShaderCompiler.PassResourceBinding(2,
                            WorldShaderCompiler.PassResourceKind.UNIFORM_BUFFER),
                    "celestialsAtlas", new WorldShaderCompiler.PassResourceBinding(3,
                            WorldShaderCompiler.PassResourceKind.SAMPLED_IMAGE),
                    "minecraftDamageModifiers", new WorldShaderCompiler.PassResourceBinding(4,
                            WorldShaderCompiler.PassResourceKind.UNIFORM_BUFFER)),
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
            "guide.rmiss.slang",
            "radiance_any_hit.rahit.slang", "shadow_any_hit.rahit.slang"})
    void compilesEveryPlainWorldStage(String moduleFileName, @TempDir Path cacheDirectory)
            throws Exception {
        try (WorldShaderCompiler compiler = compiler(cacheDirectory)) {
            assertSpirv(compiler.compilePlain(moduleFileName, WorldShaderCompiler.ENTRY_POINT), 256);
        }
    }

    private static WorldShaderCompiler compiler(Path cacheDirectory) throws Exception {
        return WorldShaderCompiler.create(cacheDirectory, dev.comfyfluffy.caustica.TestRegistries.withBuiltins().selection());
    }

    private static void assertSpirv(byte[] spirv, int minimumBytes) {
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(spirv.length > minimumBytes, "expected substantial SPIR-V, got " + spirv.length);
    }
}
