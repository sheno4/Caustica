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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertTrue(compiler.composition().rootSource().contains("typealias Sky = BuiltinSky"));
            assertTrue(compiler.composition().rootSource().contains("typealias Surface = BuiltinSurface"));
            assertTrue(compiler.composition().rootSource().contains("typealias Medium = BuiltinMedium"));
        }
        try (WorldShaderCompiler compiler = compiler(cacheDirectory.resolve("ser"))) {
            assertSpirv(compiler.compileSkyMiss(), 1024);
            assertSpirv(compiler.compileClosestHit(), 1024);
            assertSpirv(compiler.compileIndirect(true), 1024);
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
