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

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0;
             index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
