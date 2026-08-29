package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresentationSynchronizationContractTest {
    @Test
    void copyAndBlitStagesUseThePromotedVulkan13Names() throws Exception {
        String sources = Files.readString(source("RtToneLut.java"))
                + Files.readString(source("RtExposure.java"))
                + Files.readString(source("HdrPresentation.java"));

        assertFalse(sources.contains("KHRSynchronization2"));
        assertFalse(sources.contains("VK_PIPELINE_STAGE_2_COPY_BIT_KHR"));
        assertFalse(sources.contains("VK_PIPELINE_STAGE_2_BLIT_BIT_KHR"));
        assertTrue(sources.contains("VK13.VK_PIPELINE_STAGE_2_COPY_BIT"));
        assertTrue(sources.contains("VK13.VK_PIPELINE_STAGE_2_BLIT_BIT"));
        assertTrue(sources.contains("VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT"));
        assertTrue(VK13.VK_PIPELINE_STAGE_2_COPY_BIT != 0L);
        assertTrue(VK13.VK_PIPELINE_STAGE_2_BLIT_BIT != 0L);
    }

    private static Path source(String name) {
        return Path.of("src/main/java/dev/comfyfluffy/caustica/renderer/presentation").resolve(name);
    }
}
