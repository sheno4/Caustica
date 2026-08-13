package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtOpenPbrShaderContractTest {
    private static final Path SHADERS = Path.of("src/main/resources/caustica/shaders");

    @Test
    void materialInputExposesTheImplementedThinWallSubsetOnly() throws IOException {
        String api = Files.readString(SHADERS.resolve("api/caustica_types.slang"));
        assertTrue(api.contains("public float3 transmissionColor;"));
        assertTrue(api.contains("public float subsurfaceWeight;"));
        assertTrue(api.contains("public float3 subsurfaceColor;"));
        assertTrue(api.contains("public float subsurfaceScatterAnisotropy;"));
        assertFalse(api.contains("public float3 specularColor;"));
        assertFalse(api.contains("geometryOpacity"));
        assertFalse(api.contains("geometryThinWalled"));
    }

    @Test
    void thinSheetSampleAndEvaluationShareOnePdfMixture() throws IOException {
        String bsdf = Files.readString(SHADERS.resolve("world/surface_bsdf.slang"));
        assertTrue(bsdf.contains("void thinSheetFactors("));
        assertTrue(bsdf.contains("float4 surfaceLobeProbabilities("));
        assertTrue(occurrences(bsdf, "surfaceLobeProbabilities(query.surface, ndv)") == 2,
                "evaluateBsdf and sampleBsdf must use the same lobe probabilities");
        assertTrue(bsdf.contains("result.throughputWeight = evaluation.value * abs(sampledNdl)"));
        assertTrue(bsdf.contains("float3 mirroredIncoming = reflect(query.incomingDirection, n);"));
        assertTrue(bsdf.contains("sampleThinTransmission ? reflect(reflected, n) : reflected"));
    }

    @Test
    void directLightingUsesTheCanonicalBsdfEvaluator() throws IOException {
        String lighting = Files.readString(SHADERS.resolve("world/lighting.slang"));
        assertTrue(lighting.contains("BsdfEvaluation evaluation = evaluateBsdf(query);"));
        assertFalse(lighting.contains("closure.transmission"));
    }

    @Test
    void closestHitConsumesDeclaredCanonicalColors() throws IOException {
        String common = Files.readString(SHADERS.resolve("world/world_common.slang"));
        String hit = Files.readString(SHADERS.resolve("world/closest_hit.slang"));

        assertTrue(common.contains("MATERIAL_FEATURE_SUBSURFACE_COLOR_BASE = 8u;"));
        assertTrue(common.contains("MATERIAL_FEATURE_EMISSION_COLOR_BASE = 16u;"));
        assertTrue(hit.contains("surface.subsurfaceColor = float3(0.8);"));
        assertTrue(hit.contains("surface.emissionColor = float3(1.0);"));
        assertTrue(hit.contains("surface.subsurfaceColor = surface.baseColor;"));
        assertTrue(hit.contains("surface.emissionColor = surface.baseColor;"));
        assertTrue(hit.contains("material.subsurfaceColor = surface.subsurfaceColor;"));
        assertTrue(hit.contains("material.emissionColor = surface.emissionColor;"));
        assertFalse(hit.contains("LabPBR"));
        assertFalse(hit.contains("source adapter"));
    }

    @Test
    void distantNeeOwnsContinuousBsdfEmitterVisibilityWithoutBiasingGgx() throws IOException {
        String math = Files.readString(SHADERS.resolve("world/math.slang"));
        String indirect = Files.readString(SHADERS.resolve("world/indirect_core.slang"));

        assertTrue(math.contains("return a2 / (PI * d * d);"));
        assertFalse(math.contains("PI * d * d +"));
        assertTrue(indirect.contains("bool distantNeeOn = worldPush.risCandidates > 0u"));
        assertTrue(indirect.contains("showEnvironmentEmitter = !distantNeeOn;"));
        assertTrue(indirect.contains("diffuseEvent ? RAY_CONE_DIFFUSE_SPREAD"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0;
             index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
