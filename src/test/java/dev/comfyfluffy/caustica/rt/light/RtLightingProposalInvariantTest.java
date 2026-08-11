package dev.comfyfluffy.caustica.rt.light;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtLightingProposalInvariantTest {
    @Test
    void unifiedProposalPublishesExactDiscretePdfAndPhysicalConeNormalization() throws IOException {
        String source = shader("lighting.slang");

        assertTrue(source.contains("sourcePdf *= selectedWeight / sum;"));
        assertTrue(source.contains("sourcePdf = weight / totalWeight;"));
        assertTrue(source.contains("float angularWeight = 2.0 / (1.0 + outerCos);"));
        assertTrue(source.contains("uint candidateCount = worldPush.risCandidates;"));
        assertTrue(source.contains("* metersPerWorldUnit * metersPerWorldUnit"));
        assertFalse(source.contains("providerProbability"));
        assertFalse(source.contains("hasTerrainLights"));
        assertFalse(source.contains("LightGrid"));
        assertFalse(source.contains("LightAlias"));
    }

    @Test
    void linkedEmitterGatingIsIndependentOfTransientLights() throws IOException {
        String source = shader("indirect_core.slang");
        assertTrue(source.contains("linkedEmitterNeeOn = worldPush.risCandidates > 0u"));
        assertTrue(source.contains("worldPush.retainedLightInfo.z > 0 && retainedLightsPresent"));
        assertTrue(source.contains("gateEmitter = linkedEmitterNeeOn && payloadEmitterInList()"));
        assertFalse(source.contains("providerRisOn"));
        assertFalse(source.contains("terrainRisOn"));
    }

    private static String shader(String file) throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "caustica",
                "shaders", "world", file)).replace("\r\n", "\n");
    }
}
