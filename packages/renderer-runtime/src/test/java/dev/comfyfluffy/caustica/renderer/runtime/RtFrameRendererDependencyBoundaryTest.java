package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameRendererDependencyBoundaryTest {
    @Test
    void retainsItsSessionVulkanContextWithoutACompositionLookup() throws Exception {
        var context = RtFrameRenderer.class.getDeclaredField("context");
        assertSame(VulkanDeviceContext.class, context.getType());
        assertTrue(Modifier.isFinal(context.getModifiers()));

        String renderer = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java"));
        assertFalse(renderer.contains("CausticaClientComposition"));
        assertFalse(renderer.contains("static boolean enabled()"));
    }

    @Test
    void terminalFailureSkipsUiRecordingBeforeHealthyLifecycleValidation() throws IOException {
        String renderer = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java"));
        int method = renderer.indexOf("public void recordUiPasses");
        int nextMethod = renderer.indexOf("public void finishGraphicsUse", method);
        String body = renderer.substring(method, nextMethod);

        int terminalFailureGuard = body.indexOf("if (failed)");
        int missingFrameGuard = body.indexOf(
                "if (pendingGraphicsUse == null || currentTrace == null || frameSnapshot == null)");
        assertTrue(terminalFailureGuard >= 0);
        assertTrue(missingFrameGuard > terminalFailureGuard);
        assertTrue(body.substring(terminalFailureGuard, missingFrameGuard).contains("return;"));
        assertTrue(body.substring(missingFrameGuard).contains(
                "throw new IllegalStateException(\"no retained frame is available for UI recording\")"));
    }
}
