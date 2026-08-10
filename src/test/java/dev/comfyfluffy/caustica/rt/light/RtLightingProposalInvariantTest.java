package dev.comfyfluffy.caustica.rt.light;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtLightingProposalInvariantTest {
    @Test
    void terrainStratificationPublishesItsActualCandidateProbability() throws IOException {
        String source = Files.readString(Path.of("src", "main", "resources", "caustica",
                "shaders", "world", "lighting.slang")).replace("\r\n", "\n");

        assertTrue(source.contains("stratifiedLocalProbability = 1.0\n"
                + "            - float(globalCandidateCount) / float(candidateCount)"));
        assertTrue(source.contains("hasProviderLights\n"
                + "            ? (hasGridCell ? 0.75 : 0.0) : stratifiedLocalProbability"));
        assertTrue(source.contains("float angularWeight = 2.0 / (1.0 + outerCos);"));
        assertTrue(source.contains("sampleUniformCone(light.axis0, light.shapeScalar, seed)"));
        assertTrue(source.contains("hasTerrainLights = worldPush.risCandidates > 0u\n"
                + "            && pc.lightBufAddr != 0 && worldPush.lightCount > 0u"));
        assertTrue(source.contains("providerProbability = hasProviderLights ? (hasTerrainLights ? 0.25 : 1.0)"));
    }
}
