package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedSceneProgressBoundaryTest {
    @Test
    void engineWorldProgressOwnsRetainedBackendProgression() throws IOException {
        String runtime = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtRuntime.java"));
        String services = Files.readString(Path.of(
                "../engine/src/main/java/dev/comfyfluffy/caustica/engine/session/EngineSessionServices.java"));

        assertTrue(runtime.contains("world.progress();"));
        assertFalse(runtime.contains("scenes.progress();"));
        assertTrue(services.contains("scenes.progress();"));
    }

    @Test
    void worldContributionsStillDrainBeforeDeviceIdle() throws IOException {
        String runtime = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtRuntime.java"));
        int worldClose = runtime.indexOf("world.close()");
        int deviceIdle = runtime.indexOf("gpuExecutor().drainAndWaitIdle()", worldClose);

        assertTrue(worldClose >= 0 && deviceIdle > worldClose);
    }
}
