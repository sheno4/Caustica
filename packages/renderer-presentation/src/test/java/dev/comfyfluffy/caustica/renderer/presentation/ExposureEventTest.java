package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureStateData;
import java.nio.file.Path;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ExposureEventTest {
    @TempDir Path directory;

    @Test
    void delayedReadbackKeepsItsSourceFrameAndExposure() throws Exception {
        Path output = directory.resolve("exposure.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(ExposureEvent.class);
            recording.start();
            ExposureEvent.record(42, 48, 2.0f, 7, new ExposureStateData(
                    8.0f, 1, 4.0f, 3.0f, 3.0f, 0.1f, 0.2f, 7,
                    0.5f, 0.3f, 0.0f, 1.0f, 0.6f, 0.4f));
            recording.stop();
            recording.dump(output);
        }
        var events = RecordingFile.readAllEvents(output);
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals(42L, event.getLong("frameId"));
        assertEquals(48L, event.getLong("observedFrameId"));
        assertEquals(2.0f, event.getFloat("preExposure"));
        assertEquals(8.0f, event.getFloat("absoluteExposure"));
        assertEquals(4.0f, event.getFloat("controllerResidualExposure"));
    }
}
