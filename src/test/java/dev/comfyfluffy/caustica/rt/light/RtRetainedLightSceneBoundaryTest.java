package dev.comfyfluffy.caustica.rt.light;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedLightSceneBoundaryTest {
    @Test
    void debugFocusConvertsWorldCoordinatesAtThePublishedOrigin() {
        RtRetainedLightScene.DebugFocus focus = new RtRetainedLightScene.DebugFocus(
                -1_000_000.25, 2048.5, 9_000_000.75);

        assertEquals(-0.25, focus.relativeX(-1_000_000), 0.0);
        assertEquals(0.5, focus.relativeY(2048), 0.0);
        assertEquals(0.75, focus.relativeZ(9_000_000), 0.0);
    }

    @Test
    void retainedLightCoreDoesNotImportHostAdapters() throws IOException {
        for (String file : List.of("RetainedLightBatch.java", "RtRetainedLightGrid.java",
                "RtRetainedLightSceneBuilder.java", "RtRetainedLightScene.java")) {
            Path source = lightSource(file);
            assertTrue(Files.isRegularFile(source), "retained-light source is missing: " + source);
            String text = Files.readString(source);
            for (String forbidden : List.of("net.minecraft.", "net.fabricmc.",
                    "dev.comfyfluffy.caustica.minecraft.",
                    "dev.comfyfluffy.caustica.rt.terrain.")) {
                assertFalse(text.contains(forbidden),
                        file + " crossed the host firewall with " + forbidden);
            }
        }
        Path oldTerrainOwner = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "terrain", "RtLightGridManager.java").toAbsolutePath().normalize();
        assertFalse(Files.exists(oldTerrainOwner), "async light ownership returned to terrain package");
    }

    @Test
    void publicationPreservesUploadVisibilityAndGraphicsRetirementOrdering() throws IOException {
        String source = Files.readString(lightSource("RtRetainedLightScene.java"));
        int visibility = source.indexOf("ctx.gpuExecutor().markPublished(uploaded.build);");
        int publication = source.indexOf("published = next;", visibility);
        int retirement = source.indexOf(
                "old.retire(ctx, ctx.gpuExecutor().latestGraphicsUse());", publication);

        assertTrue(visibility >= 0, "uploaded generation is not published to the graphics executor");
        assertTrue(publication > visibility, "state became visible before upload publication");
        assertTrue(retirement > publication, "previous buffers retired before the state handoff");
    }

    private static Path lightSource(String file) {
        return Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "light", file).toAbsolutePath().normalize();
    }
}
