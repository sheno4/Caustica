package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtWorldPassSeparationTest {
    private static final Path WORLD = Path.of("src/main/resources/caustica/shaders/world");

    @Test
    void indirectRaygensDoNotReusePrimaryOrWriteGuides() throws IOException {
        String primary = Files.readString(WORLD.resolve("primary_rgen.slang"));
        assertTrue(primary.contains("normalGuide"));
        assertTrue(primary.contains("depthGuide"));
        for (String name : new String[]{"indirect.slang", "indirect_ser.slang"}) {
            String indirect = Files.readString(WORLD.resolve(name));
            assertFalse(indirect.contains("primary_rgen"), name);
            assertFalse(indirect.contains("runPrimary"), name);
            assertFalse(indirect.contains("normalGuide"), name);
            assertFalse(indirect.contains("albedoGuide"), name);
            assertFalse(indirect.contains("depthGuide"), name);
            assertFalse(indirect.contains("motionGuide"), name);
            assertFalse(indirect.contains("specularAlbedoGuide"), name);
            assertFalse(indirect.contains("specularMotionGuide"), name);
        }
    }

    @Test
    void hostOrdersPrimaryBarrierBeforeIndirect() throws IOException {
        String renderer = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));
        int primary = renderer.indexOf("roots, 0, trace.hitTable()");
        int barrier = renderer.indexOf("VulkanBarriers.primaryToIndirect", primary);
        int indirect = renderer.indexOf("roots, 1, trace.hitTable()", barrier);
        assertTrue(primary >= 0 && barrier > primary && indirect > barrier);
    }

    @Test
    void passFramesPublishTraceResolutionRatherThanDisplayResolution() throws IOException {
        String renderer = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));
        assertTrue(renderer.contains(
                "frameResources.renderW, frameResources.renderH, frameResources.rrOutput"));
        assertFalse(renderer.contains(
                "frameResources.displayW, frameResources.displayH, frameResources.rrOutput"));
    }
}
