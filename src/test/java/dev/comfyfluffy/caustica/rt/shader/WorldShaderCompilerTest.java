package dev.comfyfluffy.caustica.rt.shader;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldShaderCompilerTest {
    private static final ShaderSource BUILTINS = ShaderSource.classpath(WorldShaderCompilerTest.class,
            "/caustica/shaders/builtin", "surface", "sky");
    private static final ShaderDataType<Object> DATA = ShaderDataType.create("test-data");
    private static final ShaderDataType<Object> BINDING = ShaderDataType.create("test-binding");
    private static final ShaderDataType<Object> INSTANCE = ShaderDataType.create("test-instance");

    @Test
    void assignsCategoryLocalIndicesAndBuildsImplementationDataTable(@TempDir Path cache) throws Exception {
        ProgramKey surfaceKey = new ProgramKey(ProgramKey.Kind.SURFACE, 1);
        ProgramKey volumeKey = new ProgramKey(ProgramKey.Kind.VOLUME, 2);
        ProgramKey environmentKey = new ProgramKey(ProgramKey.Kind.ENVIRONMENT, 3);
        ProgramComposition program = new ProgramComposition(7, List.of(new ProgramComposition.RegistrationSet(4,
                List.of(new ProgramComposition.Surface(surfaceKey, SurfaceDefinition.of(
                                shader("caustica_error_surface", "ErrorSurface"),
                                shader("caustica_error_coverage", "ErrorCoverage"), DATA.data(41), BINDING, INSTANCE)),
                        new ProgramComposition.Volume(volumeKey, VolumeDefinition.of(
                                shader("caustica_water_surface", "WaterVolume"), DATA.data(42), BINDING, INSTANCE)),
                        new ProgramComposition.Environment(environmentKey, new EnvironmentDefinition<>(
                                shader("caustica_builtin_sky", "BuiltinEnvironment"), BINDING))))));

        // Use the builtin surface as a stand-in volume only for source-generation assertions; the
        // generated volume type is not specialized in this test.
        ProgramComposition sourceOnly = new ProgramComposition(program.revision(), List.of(
                new ProgramComposition.RegistrationSet(4, List.of(
                        program.registrations().getFirst().declarations().get(0),
                        program.registrations().getFirst().declarations().get(2)))));
        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(cache, sourceOnly)) {
            assertEquals(1, compiler.implementationIndex(surfaceKey));
            assertEquals(1, compiler.implementationIndex(environmentKey));
            assertEquals(List.of(41L), compiler.composition().implementationData());
            assertTrue(compiler.composition().rootSource().contains("ShaderDataPtr<uint64_t>"));
            assertFalse(compiler.composition().rootSource().contains("SurfaceModifier"));
            assertSpirv(compiler.compileClosestHit());
            assertSpirv(compiler.compileRadianceAnyHit());
            assertSpirv(compiler.compileSkyMiss());
        }
    }

    @Test
    void rejectsRegistrationSetsOutsideAcceptanceOrder(@TempDir Path cache) {
        ProgramComposition program = new ProgramComposition(1, List.of(
                new ProgramComposition.RegistrationSet(2, List.of()),
                new ProgramComposition.RegistrationSet(1, List.of())));
        assertThrows(IllegalArgumentException.class, () -> WorldShaderCompiler.create(cache, program));
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(BUILTINS, module, type);
    }

    private static void assertSpirv(byte[] spirv) {
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(spirv.length > 256);
    }
}
