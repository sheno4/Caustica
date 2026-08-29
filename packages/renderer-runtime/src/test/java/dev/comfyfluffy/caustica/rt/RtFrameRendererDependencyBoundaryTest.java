package dev.comfyfluffy.caustica.rt;

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
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));
        assertFalse(renderer.contains("CausticaClientComposition"));
        assertFalse(renderer.contains("static boolean enabled()"));
    }

    @Test
    void runtimeOwnsTheFrameActivityGateForExport() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtRuntime.java"));
        int export = runtime.indexOf("boolean exportLatestResidualExposureExr");
        int nextMethod = runtime.indexOf("public boolean requiresSourceWorldFallback", export);
        String method = runtime.substring(export, nextMethod);
        assertTrue(method.contains("return frameActive && session != null && session.renderer != null"));
    }
}
