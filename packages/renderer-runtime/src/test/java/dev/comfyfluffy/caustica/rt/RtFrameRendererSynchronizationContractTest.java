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

    @Test
    void worldResourceWritesArePublishedBeforePrimaryRayTracing() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));

        int resources = source.indexOf("services.passes().recordWorldResources();");
        int dependency = source.indexOf("VulkanBarriers.worldResourcesToPrimary(cmd, stack);", resources);
        int trace = source.indexOf("program.pipeline().trace(", resources);

        assertTrue(resources >= 0);
        assertTrue(dependency > resources);
        assertTrue(trace > dependency);
    }

    @Test
    void engineDescriptorHeapsAreRestoredAfterExternalRayReconstruction() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));

        int ensure = source.indexOf("rayReconstruction.ensureFeature(");
        int evaluate = source.indexOf("rrDone = rayReconstruction.evaluate(", ensure);
        int restore = source.indexOf("ctx.bindDescriptorHeaps(cmd);", evaluate);
        int fallback = source.indexOf("if (!rrDone)", evaluate);
        int exposure = source.indexOf("presentationResources().exposure().record(", fallback);

        assertTrue(ensure >= 0);
        assertTrue(evaluate > ensure);
        assertTrue(restore > evaluate);
        assertTrue(fallback > restore);
        assertTrue(exposure > restore);
    }
}
