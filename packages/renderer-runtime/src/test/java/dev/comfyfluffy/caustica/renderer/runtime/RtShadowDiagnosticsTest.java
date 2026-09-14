package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.ShadowDiagnosticsData;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RtShadowDiagnosticsTest {
    @TempDir Path temporary;

    @Test void completedReadbackPublishesSourceFrameAndUnsignedCounts() throws Exception {
        var expected = new ShadowDiagnosticsData(33, 257, 513, -1, 3, 42, 81, 1234, 5678, 34, 600, 3);
        var bytes = ByteBuffer.allocate(ShadowDiagnosticsData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        expected.write(bytes);
        Path output = temporary.resolve("shadow.jfr");
        try (var recording = new Recording()) {
            recording.enable(RtShadowDiagnostics.ShadowTraversalEvent.class);
            recording.start();
            RtShadowDiagnostics.publish(741, ShadowDiagnosticsData.read(bytes));
            recording.stop();
            recording.dump(output);
        }
        var events = RecordingFile.readAllEvents(output);
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals(741, event.getLong("frameId"));
        assertEquals(33, event.getLong("maxShadowRestartsAbove32"));
        assertEquals(257, event.getLong("maxQueryProceedAbove256"));
        assertEquals(513, event.getLong("maxShadowProceedAbove512"));
        assertEquals(4294967295L, event.getLong("unchangedOriginEvents"));
        assertEquals(3, event.getLong("repeatedAcceptedPrimitiveEvents"));
        assertEquals(42, event.getInt("firstAnomalyPixelX"));
        assertEquals(81, event.getInt("firstAnomalyPixelY"));
        assertEquals(3, event.getInt("firstAnomalyFlags"));
    }
}
