package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameRendererSynchronizationContractTest {
    @Test
    void copyStagesUseThePromotedVulkan13Name() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java"));

        assertFalse(source.contains("VK_PIPELINE_STAGE_2_COPY_BIT_KHR"));
        assertTrue(source.contains("VK13.VK_PIPELINE_STAGE_2_COPY_BIT"));
    }

    @Test
    void worldResourceWritesArePublishedBeforePrimaryRayTracing() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java"));

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
                "src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java"));

        int indirectTrace = source.indexOf("program.pipeline().trace(",
                source.indexOf("world indirect trace"));
        int publishInputs = source.indexOf("VulkanBarriers.memoryBarrier(cmd, stack);", indirectTrace);
        int invalidate = source.indexOf("ctx.invalidateDescriptorHeapsForExternalCommand(cmd);", publishInputs);
        int ensure = source.indexOf("rayReconstruction.ensureFeature(", invalidate);
        int evaluate = source.indexOf("outputReady = rayReconstruction.evaluate(", ensure);
        int restore = source.indexOf("ctx.bindDescriptorHeaps(cmd);", evaluate);
        int fallback = source.indexOf("if (!outputReady)", evaluate);
        int publishOutput = source.indexOf("VulkanBarriers.memoryBarrier(cmd, stack);", fallback);
        int exposure = source.indexOf("presentationResources().exposure().record(", fallback);

        assertTrue(indirectTrace >= 0);
        assertTrue(publishInputs > indirectTrace);
        assertTrue(invalidate > publishInputs);
        assertTrue(ensure > invalidate);
        assertTrue(evaluate > ensure);
        assertTrue(restore > evaluate);
        assertTrue(fallback > restore);
        assertTrue(publishOutput > fallback);
        assertTrue(exposure > restore);
        assertFalse(source.contains("storageImagesToExternalSampled"));
    }

    @Test
    void engineDescriptorHeapsAreRestoredWhenExternalNrdRecordingThrows() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java"));

        int recordMethod = source.indexOf("private void recordTemporalDenoiser(");
        int invalidate = source.indexOf("ctx.invalidateDescriptorHeapsForExternalCommand(commandBuffer);", recordMethod);
        int tryBlock = source.indexOf("try {", invalidate);
        int record = source.indexOf("backend.record(", tryBlock);
        int finallyBlock = source.indexOf("finally {", record);
        int restore = source.indexOf("ctx.bindDescriptorHeaps(commandBuffer);", finallyBlock);

        assertTrue(recordMethod >= 0);
        assertTrue(invalidate > recordMethod);
        assertTrue(tryBlock > invalidate);
        assertTrue(record > tryBlock);
        assertTrue(finallyBlock > record);
        assertTrue(restore > finallyBlock);
    }
}
