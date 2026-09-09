package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtFrameStatsBoundaryTest {
    @Test
    void geometrySourcesKeepTheirRecordedNamesAndPublicationTimes(@TempDir Path temporary) throws Exception {
        Path file = temporary.resolve("geometry.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.GeometryVisibility");
            recording.start();
            var telemetry = new RtTelemetryImpl();
            telemetry.beginRenderFrame();
            for (var source : RtTelemetry.GeometrySource.values()) {
                telemetry.published(telemetry.extraction(source, 3));
            }
            telemetry.frameAssembled(telemetry.publicationCutoff());
            recording.stop();
            recording.dump(file);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("dev.comfyfluffy.caustica.GeometryVisibility"))
                .toList();
        assertEquals(List.of("terrainDispatch", "terrainReady", "entity", "entityPlacement", "blockEntity", "particle"),
                events.stream().map(event -> event.getString("source")).toList());
        for (var event : events) {
            assertEquals(3, event.getInt("geometryCount"));
            assertEquals(1, event.getLong("frameId"));
            assertTrue(event.getLong("extractedNanos") <= event.getLong("publishedNanos"));
            assertTrue(event.getLong("publishedNanos") <= event.getLong("assembledNanos"));
        }
    }

    @Test
    void recordingEnablesRawSamplesWithoutConfigAndKeepsEveryStage(@TempDir Path temporary) throws Exception {
        Path file = temporary.resolve("frames.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.Frame");
            recording.enable("dev.comfyfluffy.caustica.CpuStage");
            recording.enable("dev.comfyfluffy.caustica.FrameCounter");
            recording.start();
            RtFrameStats stats = new RtFrameStats();
            stats.configureFrameMetrics(new MetricSchema(List.of(), List.of("testCounter")));
            assertTrue(RtFrameStats.enabled());
            stats.frame().beginIfInactive();
            stats.frame().endStage("frame.prepareWorldGeometry", stats.frame().startStage());
            stats.beginRenderFrame();
            stats.frame().endStage("frame.finishTrace", stats.frame().startStage());
            stats.frame().set("testCounter", 3);
            stats.frame().count("testCounter", 2);
            stats.endFrame();
            stats.beginRenderFrame();
            stats.frame().beginIfInactive();
            stats.endFrame();
            recording.stop();
            recording.dump(file);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        List<RecordedEvent> frames = events.stream().filter(e -> e.getEventType().getName().equals("dev.comfyfluffy.caustica.Frame")).toList();
        assertEquals(List.of(1L, 2L), frames.stream().map(e -> e.getLong("frameId")).toList());
        assertTrue(frames.stream().allMatch(e -> e.getLong("elapsedNanos") >= 0));
        List<RecordedEvent> stages = events.stream().filter(e -> e.getEventType().getName().equals("dev.comfyfluffy.caustica.CpuStage")).toList();
        assertEquals(2, stages.size());
        assertEquals(List.of("frame.prepareWorldGeometry", "frame.finishTrace"),
                stages.stream().map(e -> e.getString("stage")).toList());
        assertTrue(stages.stream().allMatch(e -> e.getLong("frameId") == 1L));
        assertEquals(List.of(5L, 0L), events.stream()
                .filter(e -> e.getEventType().getName().equals("dev.comfyfluffy.caustica.FrameCounter"))
                .filter(e -> e.getString("counter").equals("testCounter"))
                .map(e -> e.getLong("value")).toList());
    }

    @Test
    void metricSchemasRejectDuplicatesAndProfilesRejectLateOrRepeatedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new MetricSchema(List.of(" "), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSchema(List.of("invalid,name"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSchema(List.of("shared"), List.of("shared")));
        assertThrows(IllegalArgumentException.class, () -> new MetricSchema(List.of(
                "duplicate", "duplicate"), List.of()));
        MetricSchema base = new MetricSchema(List.of("base"), List.of());
        RtFrameStats.Profile repeated = new RtFrameStats.Profile("repeated", base, () -> 1L);
        repeated.configureMetrics(new MetricSchema(List.of(), List.of("host")));
        assertThrows(IllegalStateException.class, () -> repeated.configureMetrics(new MetricSchema(List.of(), List.of("other"))));
        RtFrameStats.Profile used = new RtFrameStats.Profile("used", base, () -> 1L);
        used.begin();
        assertThrows(IllegalStateException.class, () -> used.configureMetrics(new MetricSchema(List.of(), List.of("late"))));
    }

    @Test
    void renderFrameSerialAdvancesOnlyAtTheExplicitBoundaryAndIsInstanceOwned() {
        RtTelemetryImpl telemetry = new RtTelemetryImpl();
        RtTelemetryImpl other = new RtTelemetryImpl();
        telemetry.beginFrameIfInactive();
        assertEquals(0L, telemetry.frameSerial());
        telemetry.beginRenderFrame();
        assertEquals(1L, telemetry.frameSerial());
        assertEquals(0L, other.frameSerial());
    }
}
