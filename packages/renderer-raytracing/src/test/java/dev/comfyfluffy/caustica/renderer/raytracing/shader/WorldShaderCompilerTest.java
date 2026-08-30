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
    void bundledWorldManifestContainsEveryTransitiveEngineImport() throws Exception {
        assertEquals(java.util.Set.of(), WorldShaderCompiler.missingBundledWorldImports());
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
                        new ProgramComposition.Volume(volumeKey, VolumeDefinition.of(
                                shader("caustica_water_surface", "WaterVolume"), DATA.data(42), BINDING, INSTANCE)),
                        new ProgramComposition.Environment(environmentKey, new EnvironmentDefinition<>(
                                shader("caustica_builtin_sky", "BuiltinEnvironment"), BINDING))));

        // Use the builtin surface as a stand-in volume only for source-generation assertions; the
        // generated volume type is not specialized in this test.
        ProgramComposition sourceOnly = new ProgramComposition(List.of(
                program.declarations().get(0), program.declarations().get(2)));
        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache, sourceOnly)) {
            assertEquals(1, compiler.composition().implementationIndices().get(surfaceKey));
            assertEquals(3, compiler.composition().implementationIndices().get(environmentKey));
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
            assertSpirv(compiler.compilePrimary());
            byte[] ordinary = compiler.compileIndirect(false);
            byte[] reordered = compiler.compileIndirect(true);
            assertSpirv(ordinary);
            assertSpirv(reordered);
            assertVulkan14(cache.resolve("indirect-ordinary.spv"), ordinary);
            assertVulkan14(cache.resolve("indirect-ser.spv"), reordered);
        }
    }

    @Test
    void retainedTransportHasDistinctQueueProducerConsumersAndNonconstantGuides() throws Exception {
        String primary = shaderSource("primary_rgen.slang");
        String indirect = shaderSource("indirect.slang");
        String reordered = shaderSource("indirect_ser.slang");
        String ordinaryTrace = shaderSource("retained_trace_ordinary.slang");
        String reorderedTrace = shaderSource("retained_trace_reordered.slang");
        String core = shaderSource("retained_indirect.slang");
        String closest = shaderSource("closest_hit.slang");
        String queue = shaderSource("path_queue_types.slang");
        String lights = shaderSource("retained_lights.slang");
        String bake = shaderSource("nee_at_bake.slang");
        String shadow = shaderSource("shadow_any_hit.rahit.slang");
        String world = shaderSource("world_minimal.slang");
        String miss = shaderSource("sky_miss.slang");
        String stablePlanes = shaderSource("stable_planes.slang");
        String bsdf = shaderSource("surface_bsdf.slang");

        assertFalse(indirect.contains("primary_rgen"));
        assertFalse(reordered.contains("primary_rgen"));
        assertTrue(indirect.contains("RetainedOrdinaryTrace"));
        assertTrue(reordered.contains("RetainedReorderedTrace"));
        assertTrue(primary.contains("queue[pixelIndex] = packRetainedPath(emptyState, PATH_NO_NEXT)"));
        assertTrue(primary.contains("continuation.throughput *= transmittance"));
        assertTrue(primary.contains("ray.Origin = frame.camOffset"));
        assertTrue(primary.contains("ray.Direction = normalize(farPoint.xyz)"));
        assertTrue(primary.contains("frame.jitter * (2.0 / float2(extent))"));
        assertTrue(primary.contains("depth = currentClip.w"));
        assertFalse(primary.contains("depth = currentClip.z / currentClip.w"));
        assertTrue(primary.contains("RAY_FLAG_NONE, WORLD_RAY_MASK_PRIMARY"));
        assertTrue(primary.contains("RAY_FLAG_NONE, WORLD_RAY_MASK_SECONDARY"));
        assertTrue(ordinaryTrace.contains("RAY_FLAG_NONE, WORLD_RAY_MASK_SECONDARY"));
        assertTrue(reorderedTrace.contains("RAY_FLAG_NONE,\n                WORLD_RAY_MASK_SECONDARY"));
        assertFalse(primary.contains("ray.Origin = nearPoint.xyz + frame.camOffset"));
        assertTrue(closest.contains("queue[payload.queueRecordIndex]"));
        assertTrue(core.contains("payload.queueRecordIndex = recordIndex"));
        assertTrue(core.contains("payload.previousNeeProposalMode = state.proposalMode"));
        assertTrue(core.contains("payload.currentNeeProposalMode = NEE_AT_PROPOSAL_GLOBAL"));
        assertTrue(core.contains("bounce <= frame.maxBounces"));
        assertTrue(core.contains("stablePlaneAddRadiance(pixel, accumulated)"));
        assertFalse(core.contains("normalGuide"));
        assertTrue(queue.contains("MAX_PATH_SEGMENTS = 2u"));

        assertFalse(primary.contains("float4(0.0, 0.0, 1.0, 1.0)"));
        assertTrue(primary.contains("normal = unpackNormalOct(primary.guideNormal)"));
        assertTrue(primary.contains("previousHitPosition"));
        assertTrue(primary.contains("stablePlaneEndpoint(primary, frame)"));
        assertTrue(primary.contains("return primaryStablePlaneEndpoint(primary)"));
        assertTrue(primary.contains("WORLD_PATH_MISS | WORLD_PATH_GUIDE_CONTINUATION"));
        assertTrue(primary.contains("segment < STABLE_PLANE_MAX_DELTA_SEGMENTS"));
        assertTrue(primary.contains("next.pathFlags = WORLD_PATH_GUIDE"));
        assertTrue(primary.contains("primary.pathFlags & WORLD_PATH_EMISSIVE"));
        assertTrue(closest.contains("payload.pathFlags |= WORLD_PATH_EMISSIVE"));
        assertFalse(primary.contains("specularMotionGuide)[pixel] = motion"));
        assertTrue(closest.contains("reflectionScore >= transmissionScore"));
        assertTrue(closest.contains("WORLD_PATH_GUIDE_CONTINUATION"));
        String guideSelector = closest.substring(closest.indexOf("retainedSelectStablePlaneContinuation"),
                closest.indexOf("BsdfSample retainedSampleContinuation"));
        assertFalse(guideSelector.contains("retainedLightRandom"));
        assertFalse(guideSelector.contains("payload.volumeAbsorption ="));
        assertFalse(guideSelector.contains("payload.volumeIor ="));
        assertFalse(guideSelector.contains("payload.pathFlags |= WORLD_PATH_VOLUME_ACTIVE"));
        assertFalse(guideSelector.contains("payload.pathFlags &= ~WORLD_PATH_VOLUME_ACTIVE"));
        assertTrue(guideSelector.contains("payload.guideContinuationAbsorption"));
        assertTrue(guideSelector.contains("surfaceLobeProbabilities"));
        assertTrue(closest.contains("retainedVolumeBoundaryWeight(record, surface)"));
        assertTrue(closest.contains("if (volumeBoundary) sampledSurface.transmission_weight = 0.0"));
        assertTrue(bsdf.contains("public float4 surfaceLobeProbabilities"));
        assertTrue(shadow.contains("1.0 - clamp(surface.base_metalness"));
        assertTrue(stablePlanes.contains("STABLE_PLANE_MAX_DELTA_SEGMENTS = 3u"));
        assertFalse(stablePlanes.contains("StablePlaneSet"));
        assertFalse(stablePlanes.contains("plane0"));
        assertFalse(stablePlanes.contains("rawRadiance"));
        assertTrue(stablePlanes.contains("sample.containsTransmission ? 1.0 : 0.0"));
        assertTrue(primary.contains("plane.radiance = radiance"));
        assertTrue(primary.contains("plane.reflectionCount = endpoint.reflectionCount"));
        assertTrue(primary.contains("plane.containsTransmission = endpoint.containsTransmission"));
        assertFalse(primary.contains("deltaChainDepth == 0u ? radiance"));
        assertFalse(primary.contains("radiance * endpoint"));
        assertTrue(primary.contains("currentPathLength += next.hitDistance"));
        assertTrue(primary.contains("previousPathLength += length(next.previousHitPosition"));
        assertTrue(primary.contains("stablePlaneUnfoldPoint(current.hitPosition, false"));
        assertTrue(primary.contains("stablePlaneUnfoldPoint(current.previousHitPosition, true"));
        assertFalse(primary.contains("stablePlaneUnfoldNormal"));
        assertFalse(primary.contains("virtualNormal"));
        assertTrue(primary.contains("if (!result.containsTransmission)"));
        assertTrue(primary.contains("currentDirection * currentPathLength"));
        assertTrue(primary.contains("previousDirection * previousPathLength"));
        assertTrue(primary.contains("currentCameraRelative = primary.hitPosition - frame.camOffset"));
        assertTrue(primary.contains("previousCameraRelative = primary.previousHitPosition"));
        assertTrue(primary.contains("endpoint.reflectionCount > 0u && !endpoint.containsTransmission"));
        assertTrue(primary.contains("float4 currentVirtualClip"));
        assertTrue(primary.contains("endpoint.virtualPosition - frame.camOffset"));
        assertTrue(primary.contains("float4 previousVirtualClip"));
        assertTrue(primary.contains("endpoint.previousVirtualPosition - frame.camOffset + frame.camDelta"));
        assertTrue(primary.contains("specularMotion = (previousVirtualClip.xy"));
        assertTrue(primary.contains("plane.primaryDepth = depth"));
        assertTrue(primary.contains("plane.primaryMotion = motion"));
        assertTrue(stablePlanes.contains("point - 2.0 * dot(point - planePosition, planeNormal)"));
        assertFalse(stablePlanes.contains("direction - 2.0 * dot(direction, planeNormal)"));

        assertTrue(lights.contains("pixelFeedback[pixelIndex] = event"));
        assertFalse(lights.contains("InterlockedCompareExchange"));
        assertTrue(bake.contains("InterlockedAdd(counts[slot], 1u)"));
        assertTrue(lights.contains("dot(fromLight, forward) < cos(light.axisU.w)"));
        assertFalse(lights.contains("RETAINED_LIGHT_POINT"));
        assertFalse(lights.contains("tan(light.axisU.w)"));
        assertFalse(lights.contains("normalize(light.axisV.xyz)"));
        assertFalse(lights.contains("asuint(RayTCurrent())"));
        assertFalse(bake.contains("previousMotionIndex"));
        assertFalse(bake.contains("previousDepthIndex"));
        assertTrue(bake.contains("GroupMemoryBarrierWithGroupSync"));
        assertTrue(bake.contains("uint2(localLights[slot], localScan[slot])"));
        assertTrue(bake.contains("blockScan[lane]"));
        assertTrue(bake.contains("chunk += 64u"));
        assertTrue(bake.contains("blockScanNext[lane]"));
        assertFalse(bake.contains("if (dispatchIndex == 0u) bakeGlobal"));
        assertTrue(bake.contains("+ paddedExtent - jitter) % paddedExtent"));
        assertTrue(lights.contains("(pixel + jitter) % (tileCount * tileSize)"));
        assertTrue(lights.contains("while (low < high)"));
        assertTrue(lights.contains("neeAtLocalAvailable(state, pixel)"));
        assertTrue(closest.contains("volumeIor = max(volume.indexOfRefraction, 1.0)"));
        assertTrue(closest.contains("shadow.pathFlags = WORLD_PATH_SHADOW"));
        assertTrue(closest.contains("WORLD_RAY_MASK_SECONDARY, 1u, 2u, 1u"));
        assertTrue(closest.contains("shadow.volumeIor = currentMediumIor"));
        assertTrue(closest.contains("incomingFeedbackThroughput * estimator"));
        assertTrue(core.contains("payload.feedbackThroughput = throughput"));
        assertTrue(lights.contains("isfinite(contribution)"));
        assertTrue(shadow.contains("evaluateBoundaryLighting"));
        assertTrue(shadow.contains("IgnoreHit()"));
        assertTrue(world.contains("query.showEnvironmentEmitters = showEmitters"));
        assertTrue(world.contains("query.bindingData = frame[0].environmentBinding"));
        assertTrue(world.contains("WORLD_RAY_MASK_SECONDARY = 0x01u"));
        assertTrue(world.contains("WORLD_RAY_MASK_PRIMARY = 0x02u"));
        assertTrue(miss.contains(
                "environments.evaluateEnvironment(worldEnvironmentImplementation()"));
        assertFalse(miss.contains("environments.evaluateEnvironment(0u"));
        assertTrue(miss.contains("payload.previousBsdfPdf <= 0.0"));
        assertTrue(closest.contains("abs(dot(closure.shadingNormal, light.direction))"));
        assertTrue(closest.contains("boundaryWeight > 0.0"));
        assertTrue(shadow.contains("surface.transmission_weight"));
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

    private static String shaderSource(String name) throws Exception {
        try (var input = WorldShaderCompilerTest.class.getResourceAsStream(
                "/caustica/shaders/world/" + name)) {
            if (input == null) throw new IllegalStateException("missing shader " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
