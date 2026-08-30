package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NrdComposeShaderContractTest {
    @Test
    void remodulatesMatchingLobesAndAppliesPreExposureOnce() throws IOException {
        String shader = Files.readString(Path.of("shaders/pipelines/nrd_compose/main.comp.slang"));

        assertTrue(shader.contains("nrdRadiance(denoisedDiffuse[pixel].rgb) * diffuseBsdf"));
        assertTrue(shader.contains("nrdRadiance(denoisedSpecular[pixel].rgb) * specularBsdf"));
        assertEquals(2, occurrences(shader, "max(diffuseAlbedo[pixel].rgb, float3(1.0e-4))")
                + occurrences(shader, "max(specularAlbedo[pixel].rgb, float3(1.0e-4))"));
        assertTrue(shader.contains("stable = max(stableRadiance[pixel].rgb, float3(0.0))"));
        assertTrue(shader.contains("(stable + diffuse + specular) * pc.preExposure"));
        assertEquals(1, occurrences(shader, "pc.preExposure"));
        assertTrue(shader.contains("pc.signalEncoding == 0u"));
        assertTrue(shader.contains("float t = encoded.x - encoded.z"));
        assertTrue(shader.contains("float3(t + encoded.y, encoded.x + encoded.z, t - encoded.y)"));
        assertTrue(shader.contains("Alpha carries the method-specific hit distance"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
