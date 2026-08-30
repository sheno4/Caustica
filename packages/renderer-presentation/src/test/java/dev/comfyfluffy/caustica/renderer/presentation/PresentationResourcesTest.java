package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PresentationResourcesTest {
    @Test
    void startsUnsizedWithItsOwnExposureController() {
        RtExposure.Settings settings = new RtExposure.Settings("manual", 0.0f, 0.18f,
                1.0f, 1.0f, 0.1f, 0.9f, 1, 1.0f, 0.0f,
                1.0f, 1.0f, true, false, 2.2f);
        PresentationResources resources = new PresentationResources(RtLookPackage.loadDefault(), settings);
        assertFalse(resources.matches(1920, 1080));
        assertSame(resources.exposure(), resources.exposure());
    }

    @Test
    void ownsTheResizeIndependentNrdComposePipeline() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/presentation/PresentationResources.java"));

        assertEquals(1, occurrences(source, "RtNrdComposePipeline.create(context)"));
        assertEquals(1, occurrences(source, "nrdComposePipeline.destroy()"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
