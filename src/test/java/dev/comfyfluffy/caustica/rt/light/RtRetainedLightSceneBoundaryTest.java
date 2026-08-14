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
        for (String file : List.of("RetainedLightBatch.java", "RtRetainedLightSceneBuilder.java",
                "RtRetainedLightScene.java", "RtLightScene.java")) {
            Path source = lightSource(file);
            assertTrue(Files.isRegularFile(source), "retained-light source is missing: " + source);
            String text = Files.readString(source);
            for (String forbidden : List.of("net.minecraft.", "net.fabricmc.", "net.neoforged.",
                    "dev.comfyfluffy.caustica.minecraft.",
                    "dev.comfyfluffy.caustica.rt.terrain.")) {
                assertFalse(text.contains(forbidden),
                        file + " crossed the host firewall with " + forbidden);
            }
        }
        Path oldTerrainOwner = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "terrain", "RtLightGridManager.java").toAbsolutePath().normalize();
        assertFalse(Files.exists(oldTerrainOwner), "async light ownership returned to terrain package");
        assertFalse(Files.exists(lightSource("RtRetainedLightGrid.java")));
        assertFalse(Files.exists(lightSource("RtProviderLights.java")));
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

    @Test
    void transientSlotLifetimeStartsOnlyAfterSuccessfulSubmission() throws IOException {
        Path composite = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "RtComposite.java").toAbsolutePath().normalize();
        String source = Files.readString(composite);
        int execute = source.indexOf("submission.execute(cmd);");
        int mark = source.indexOf("lightScene.markGraphicsUse(frameLights, graphicsUse);", execute);
        assertTrue(execute >= 0);
        assertTrue(mark > execute, "transient ring slot was marked before successful submission");

        String scene = Files.readString(lightSource("RtLightScene.java"));
        assertTrue(scene.contains("if (descriptors.isEmpty()) return Frame.EMPTY;"));
        assertTrue(scene.indexOf("if (descriptors.isEmpty()) return Frame.EMPTY;")
                < scene.indexOf("ensureRing(ctx);"), "empty frames should not consume a ring slot");
    }

    private static Path lightSource(String file) {
        return Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "light", file).toAbsolutePath().normalize();
    }
}
