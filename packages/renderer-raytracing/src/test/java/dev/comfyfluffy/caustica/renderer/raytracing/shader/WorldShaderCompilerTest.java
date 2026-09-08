package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldShaderCompilerTest {
    private static final ShaderSource BUILTINS = ShaderSource.classpath(WorldShaderCompilerTest.class,
            "/caustica/shaders/builtin", "surface", "sky");
    private static final byte[] SPIRV_DEBUG_INFO_IMPORT =
            "NonSemantic.Shader.DebugInfo.100\0".getBytes(StandardCharsets.US_ASCII);
    private static final ShaderDataType<Object> DATA = ShaderDataType.create("test-data");
    private static final ShaderDataType<Object> BINDING = ShaderDataType.create("test-binding");
    private static final ShaderDataType<Object> INSTANCE = ShaderDataType.create("test-instance");
    private static SlangRuntime runtime;

    @BeforeAll
    static void createRuntime() {
        runtime = new SlangRuntime(new SlangRuntimeConfig(
                Path.of("build/test-slang-extraction"),
                Optional.empty()));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.shutdown();
    }

    @Test
    void preservesStableCategorySlotsAndBuildsImplementationDataTable(@TempDir Path cache) throws Exception {
        ProgramKey surfaceKey = new ProgramKey(ProgramKey.Kind.SURFACE, 1);
        ProgramKey volumeKey = new ProgramKey(ProgramKey.Kind.VOLUME, 2);
        ProgramKey environmentKey = new ProgramKey(ProgramKey.Kind.ENVIRONMENT, 3);
        ProgramComposition program = new ProgramComposition(
                List.of(new ProgramComposition.Surface(surfaceKey, SurfaceDefinition.of(
                                shader("caustica_error_surface", "ErrorSurface"),
                                shader("caustica_error_coverage", "ErrorCoverage"), DATA.data(41), BINDING, INSTANCE)),
                        new ProgramComposition.Volume(volumeKey, new VolumeDefinition<>(
                                shader("caustica_water_surface", "WaterVolume"), DATA.data(42), BINDING, INSTANCE)),
                        new ProgramComposition.Environment(environmentKey, new EnvironmentDefinition<>(
                                shader("caustica_builtin_sky", "BuiltinEnvironment"), BINDING))));

        // Use the builtin surface as a stand-in volume only for source-generation assertions; the
        // generated volume type is not specialized in this test.
        ProgramComposition sourceOnly = new ProgramComposition(List.of(
                program.declarations().get(0), program.declarations().get(2)));
        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache, sourceOnly)) {
            assertTrue(compiler.composition().rootSource().contains("case 3u:"));
            assertEquals(List.of(41L), compiler.composition().implementationData());
            assertTrue(compiler.composition().rootSource().contains("ShaderDataPtr<uint64_t>"));
            assertTrue(compiler.composition().rootSource().contains(
                    "case 0u: { BuiltinEnvironment value;"));
            assertTrue(compiler.composition().rootSource().contains(
                    "default: { ErrorEnvironment value;"));
            assertFalse(compiler.composition().rootSource().contains("SurfaceModifier"));
            assertSpirv(compiler.compileClosestHit());
            assertSpirv(compiler.compileRadianceAnyHit());
            assertSpirv(compiler.compileShadowAnyHit());
            assertSpirv(compiler.compileSkyMiss());
            assertSpirv(compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
            assertSpirv(compiler.compileBuildStablePlanes());
            byte[] ordinary = compiler.compileFillStablePlanes(false);
            byte[] reordered = compiler.compileFillStablePlanes(true);
            assertSpirv(ordinary);
            assertSpirv(reordered);
            assertVulkan14(cache.resolve("fill-stable-planes-ordinary.spv"), ordinary);
            assertVulkan14(cache.resolve("fill-stable-planes-ser.spv"), reordered);
        }
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(BUILTINS, module, type);
    }

    private static void assertSpirv(byte[] spirv) {
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(spirv.length > 256);
        assertTrue(contains(spirv, SPIRV_DEBUG_INFO_IMPORT),
                "SPIR-V must embed NonSemantic.Shader.DebugInfo.100");
    }

    private static boolean contains(byte[] contents, byte[] expected) {
        for (int offset = 0; offset <= contents.length - expected.length; offset++) {
            int index = 0;
            while (index < expected.length && contents[offset + index] == expected[index]) index++;
            if (index == expected.length) return true;
        }
        return false;
    }

    private static void assertVulkan14(Path output, byte[] spirv) throws Exception {
        Files.write(output, spirv);
        Process validator = new ProcessBuilder(System.getProperty("caustica.test.spirvVal"),
                "--target-env", "vulkan1.4", output.toString()).redirectErrorStream(true).start();
        String diagnostics = new String(validator.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, validator.waitFor(), diagnostics);
    }


}
