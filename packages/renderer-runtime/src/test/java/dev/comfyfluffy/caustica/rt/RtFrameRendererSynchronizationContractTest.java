package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameRendererSynchronizationContractTest {
    @Test
    void copyStagesUseThePromotedVulkan13Name() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));

        assertFalse(source.contains("VK_PIPELINE_STAGE_2_COPY_BIT_KHR"));
        assertTrue(source.contains("VK13.VK_PIPELINE_STAGE_2_COPY_BIT"));
    }
}
