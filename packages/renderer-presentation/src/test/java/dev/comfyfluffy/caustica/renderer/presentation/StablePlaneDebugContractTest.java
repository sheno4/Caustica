package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class StablePlaneDebugContractTest {
    @Test
    void debugPresentationExposesStablePlaneIdentityPrimaryGuidesAndRadiance() throws Exception {
        String shader = Files.readString(Path.of(
                "shaders/pipelines/debug_present/main.comp.slang"));
        String push = Files.readString(Path.of("shaders/common/display_common.slang"));

        assertTrue(push.contains("traceRadianceImageIndex"));
        assertTrue(push.contains("stablePlaneMetadataImageIndex"));
        assertTrue(shader.contains("pc.debugView == 10u"));
        assertTrue(shader.contains("pc.debugView == 11u"));
        assertTrue(shader.contains("pc.debugView == 12u"));
        assertTrue(shader.contains("pc.debugView == 13u"));
        assertTrue(shader.contains("pc.debugView == 14u"));
        assertTrue(shader.contains("pc.debugView == 15u"));
        assertTrue(shader.contains("float primaryDepth = max(gDepth[guidePix], 0.0)"));
        assertTrue(shader.contains("gMotion[guidePix] * motionToDisplay"));
        assertTrue(shader.contains("traceRadiance[guidePix]"));
        assertTrue(shader.contains("float3(metadata.z, metadata.w * metadata.y, metadata.y)"));
    }
}
