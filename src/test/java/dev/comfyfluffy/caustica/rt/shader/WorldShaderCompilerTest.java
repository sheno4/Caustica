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
import java.nio.charset.StandardCharsets;
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
    void bundledWorldManifestContainsEveryTransitiveEngineImport() throws Exception {
        assertEquals(java.util.Set.of(), WorldShaderCompiler.missingBundledWorldImports());
    }

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
            assertSpirv(compiler.compilePrimary());
            assertSpirv(compiler.compileIndirect(false));
            assertSpirv(compiler.compileIndirect(true));
        }
    }

    @Test
    void retainedTransportHasDistinctQueueProducerConsumersAndNonconstantGuides() throws Exception {
        String primary = shaderSource("primary_rgen.slang");
        String indirect = shaderSource("indirect.slang");
        String reordered = shaderSource("indirect_ser.slang");
        String core = shaderSource("retained_indirect.slang");
        String closest = shaderSource("closest_hit.slang");
        String queue = shaderSource("path_queue_types.slang");
        String lights = shaderSource("retained_lights.slang");
        String bake = shaderSource("nee_at_bake.slang");
        String shadow = shaderSource("shadow_any_hit.rahit.slang");
        String world = shaderSource("world_minimal.slang");
        String miss = shaderSource("sky_miss.slang");

        assertFalse(indirect.contains("primary_rgen"));
        assertFalse(reordered.contains("primary_rgen"));
        assertTrue(indirect.contains("RetainedOrdinaryTrace"));
        assertTrue(reordered.contains("RetainedReorderedTrace"));
        assertTrue(primary.contains("queue[pixelIndex] = packRetainedPath(emptyState, PATH_NO_NEXT)"));
        assertTrue(primary.contains("continuation.throughput *= transmittance"));
        assertTrue(closest.contains("queue[payload.queueRecordIndex]"));
        assertTrue(core.contains("payload.queueRecordIndex = recordIndex"));
        assertTrue(core.contains("payload.previousNeeProposalMode = state.proposalMode"));
        assertTrue(core.contains("payload.currentNeeProposalMode = NEE_AT_PROPOSAL_GLOBAL"));
        assertTrue(core.contains("bounce <= frame.maxBounces"));
        assertTrue(core.contains("primary.xyz + indirectRadiance * frame.preExposure"));
        assertFalse(core.contains("normalGuide"));
        assertTrue(queue.contains("MAX_PATH_SEGMENTS = 2u"));

        assertFalse(primary.contains("float4(0.0, 0.0, 1.0, 1.0)"));
        assertTrue(primary.contains("unpackNormalOct(payload.guideNormal)"));
        assertTrue(primary.contains("previousHitPosition"));
        assertTrue(primary.contains("primarySpecularMotion"));
        assertTrue(primary.contains("primary.roughness >= 0.35"));
        assertTrue(primary.contains("secondary.pathFlags = WORLD_PATH_GUIDE"));
        assertFalse(primary.contains("specularMotionGuide)[pixel] = motion"));

        assertTrue(lights.contains("pixelIndex * 2u + 1u"));
        assertTrue(lights.contains("dot(fromLight, forward) < cos(light.axisU.w)"));
        assertFalse(lights.contains("RETAINED_LIGHT_POINT"));
        assertFalse(lights.contains("tan(light.axisU.w)"));
        assertFalse(lights.contains("normalize(light.axisV.xyz)"));
        assertFalse(lights.contains("asuint(RayTCurrent())"));
        assertFalse(bake.contains("previousMotionIndex"));
        assertFalse(bake.contains("previousDepthIndex"));
        assertTrue(bake.contains("GroupMemoryBarrierWithGroupSync"));
        assertTrue(bake.contains("uint2(localLights[slot], localScan[slot])"));
        assertTrue(bake.contains("globalPowerSums[lane]"));
        assertTrue(bake.contains("chunk += 64u"));
        assertTrue(bake.contains("globalScanNext[lane]"));
        assertFalse(bake.contains("if (dispatchIndex == 0u) bakeGlobal"));
        assertTrue(bake.contains("permutedLocal = (local + jitter) % state.tileSize"));
        assertTrue(bake.contains("bool inExtent = pixel.x < state.extentWidth"));
        assertTrue(lights.contains("while (low < high)"));
        assertTrue(lights.contains("neeAtLocalAvailable(state, pixel)"));
        assertTrue(closest.contains("volumeIor = max(volume.indexOfRefraction, 1.0)"));
        assertTrue(closest.contains("shadow.pathFlags = WORLD_PATH_SHADOW"));
        assertTrue(closest.contains("shadow.volumeIor = currentMediumIor"));
        assertTrue(lights.contains("isfinite(contribution)"));
        assertTrue(shadow.contains("evaluateBoundaryLighting"));
        assertTrue(shadow.contains("IgnoreHit()"));
        assertTrue(world.contains("query.showEnvironmentEmitters = showEmitters"));
        assertTrue(miss.contains("payload.previousBsdfPdf <= 0.0"));
        assertTrue(closest.contains("abs(dot(closure.shadingNormal, light.direction))"));
        assertTrue(closest.contains("boundaryWeight > 0.0"));
        assertTrue(shadow.contains("surface.transmission_weight"));
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

    private static String shaderSource(String name) throws Exception {
        try (var input = WorldShaderCompilerTest.class.getResourceAsStream(
                "/caustica/shaders/world/" + name)) {
            if (input == null) throw new IllegalStateException("missing shader " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
