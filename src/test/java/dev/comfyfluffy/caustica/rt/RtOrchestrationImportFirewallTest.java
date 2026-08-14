package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtOrchestrationImportFirewallTest {
    private static final List<String> SOURCES = List.of(
            "rt/GpuContext.java",
            "rt/RtGpuExecutor.java",
            "rt/RtFramePresenter.java",
            "rt/RtRuntime.java",
            "rt/RtComposite.java",
            "rt/pass/RenderPassManager.java",
            "rt/pipeline/RtExposure.java",
            "rt/pipeline/RtDlssFg.java",
            "rt/pipeline/RtDlssRr.java");

    @Test
    void rendererOrchestrationDoesNotImportHostAdapters() throws IOException {
        Path javaRoot = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica")
                .toAbsolutePath().normalize();
        for (String relative : SOURCES) {
            Path source = javaRoot.resolve(relative);
            List<String> violations = Files.readAllLines(source).stream()
                    .filter(line -> line.startsWith("import "))
                    .filter(RtOrchestrationImportFirewallTest::isHostImport)
                    .toList();
            assertTrue(violations.isEmpty(), source + " crossed the renderer host firewall:\n"
                    + String.join("\n", violations));
        }
    }

    private static boolean isHostImport(String line) {
        return line.contains("com.mojang.")
                || line.contains("net.minecraft.")
                || line.contains("net.fabricmc.")
                || line.contains("net.neoforged.")
                || line.contains("dev.comfyfluffy.caustica.client.")
                || line.contains("dev.comfyfluffy.caustica.mixin.")
                || line.contains("dev.comfyfluffy.caustica.minecraft.");
    }
}
